/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.search;

import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiPostingsEnum;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.StringHelper;
import org.apache.solr.common.Callable;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.TrieField;
import org.apache.solr.search.join.GraphPointsCollector;
import org.apache.solr.search.join.ScoreJoinQParserPlugin;
import org.apache.solr.util.RTimer;
import org.apache.solr.util.RefCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JoinQParserPlugin extends QParserPlugin {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String NAME = "join";
  /** Choose the internal algorithm */
  private static final String METHOD = "method";

  private static class JoinParams {
    final String fromField;
    final String fromCore;
    final Query fromQuery;
    final long fromCoreOpenTime;
    final String toField;

    public JoinParams(String fromField, String fromCore, Query fromQuery, long fromCoreOpenTime, String toField) {
      this.fromField = fromField;
      this.fromCore = fromCore;
      this.fromQuery = fromQuery;
      this.fromCoreOpenTime = fromCoreOpenTime;
      this.toField = toField;
    }
  }

  private enum Method {
    index {
      @Override
      Query makeFilter(QParser qparser) throws SyntaxError {
        final JoinParams jParams = parseJoin(qparser);
        final JoinQuery q = new JoinQuery(jParams.fromField, jParams.toField, jParams.fromCore, jParams.fromQuery);
        q.fromCoreOpenTime = jParams.fromCoreOpenTime;
        return q;
      }
    },
    dvWithScore {
      @Override
      Query makeFilter(QParser qparser) throws SyntaxError {
        return new ScoreJoinQParserPlugin().createParser(qparser.qstr, qparser.localParams, qparser.params, qparser.req).parse();
      }
    },
    topLevelDV {
      @Override
      Query makeFilter(QParser qparser) throws SyntaxError {
        final JoinParams jParams = parseJoin(qparser);
        final JoinQuery q = new TopLevelJoinQuery(jParams.fromField, jParams.toField, jParams.fromCore, jParams.fromQuery);
        q.fromCoreOpenTime = jParams.fromCoreOpenTime;
        return q;
      }
    };

    abstract Query makeFilter(QParser qparser) throws SyntaxError;

    JoinParams parseJoin(QParser qparser) throws SyntaxError {
      final String fromField = qparser.getParam("from");
      final String fromIndex = qparser.getParam("fromIndex");
      final String toField = qparser.getParam("to");
      final String v = qparser.localParams.get(QueryParsing.V);
      final String coreName;

      Query fromQuery;
      long fromCoreOpenTime = 0;

      if (fromIndex != null && !fromIndex.equals(qparser.req.getCore().getCoreDescriptor().getName()) ) {
        CoreContainer container = qparser.req.getCore().getCoreContainer();

        // if in SolrCloud mode, fromIndex should be the name of a single-sharded collection
        coreName = ScoreJoinQParserPlugin.getCoreName(fromIndex, container);

        final SolrCore fromCore = container.getCore(coreName);
        if (fromCore == null) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
              "Cross-core join: no such core " + coreName);
        }

        RefCounted<SolrIndexSearcher> fromHolder = null;
        LocalSolrQueryRequest otherReq = new LocalSolrQueryRequest(fromCore, qparser.params);
        try {
          QParser parser = QParser.getParser(v, otherReq);
          fromQuery = parser.getQuery();
          fromHolder = fromCore.getRegisteredSearcher();
          if (fromHolder != null) fromCoreOpenTime = fromHolder.get().getOpenNanoTime();
        } finally {
          otherReq.close();
          fromCore.close();
          if (fromHolder != null) fromHolder.decref();
        }
      } else {
        coreName = null;
        QParser fromQueryParser = qparser.subQuery(v, null);
        fromQueryParser.setIsFilter(true);
        fromQuery = fromQueryParser.getQuery();
      }

      final String indexToUse = coreName == null ? fromIndex : coreName;
      return new JoinParams(fromField, indexToUse, fromQuery, fromCoreOpenTime, toField);
    }
  }

  @Override
  public QParser createParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    return new QParser(qstr, localParams, params, req) {

      @Override
      public Query parse() throws SyntaxError {
        if (localParams != null && localParams.get(METHOD) != null) {
          // TODO Make sure 'method' is valid value here and give users a nice error
          final Method explicitMethod = Method.valueOf(localParams.get(METHOD));
          return explicitMethod.makeFilter(this);
        }

        // Legacy join behavior before introduction of SOLR-13892
        if(localParams!=null && localParams.get(ScoreJoinQParserPlugin.SCORE)!=null) {
          return new ScoreJoinQParserPlugin().createParser(qstr, localParams, params, req).parse();
        } else {
          return Method.index.makeFilter(this);
        }
      }
    };
  }

  /**
   * A helper method for other plugins to create (non-scoring) JoinQueries wrapped around arbitrary queries against the same core.
   * 
   * @param subQuery the query to define the starting set of documents on the "left side" of the join
   * @param fromField "left side" field name to use in the join
   * @param toField "right side" field name to use in the join
   */
  public static Query createJoinQuery(Query subQuery, String fromField, String toField) {
    return new JoinQuery(fromField, toField, null, subQuery);
  }
  
}


class JoinQuery extends Query {
  String fromField;
  String toField;
  String fromIndex; // TODO: name is missleading here compared to JoinQParserPlugin usage - here it must be a core name
  Query q;
  long fromCoreOpenTime;

  public JoinQuery(String fromField, String toField, String coreName, Query subQuery) {
    assert null != fromField;
    assert null != toField;
    assert null != subQuery;
    
    this.fromField = fromField;
    this.toField = toField;
    this.q = subQuery;
    
    this.fromIndex = coreName; // may be null
  }

  public Query getQuery() { return q; }

  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    // don't rewrite the subQuery
    return super.rewrite(reader);
  }

  @Override
  public void visit(QueryVisitor visitor) {

  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
    return new JoinQueryWeight((SolrIndexSearcher) searcher, scoreMode, boost);
  }

  protected class JoinQueryWeight extends ConstantScoreWeight {
    SolrIndexSearcher toSearcher;
    ResponseBuilder rb;
    ScoreMode scoreMode;
    final boolean isSameCoreJoin;

    public JoinQueryWeight(SolrIndexSearcher searcher, ScoreMode scoreMode, float boost) {
      super(JoinQuery.this, boost);
      this.scoreMode = scoreMode;
      SolrRequestInfo info = SolrRequestInfo.getRequestInfo();
      if (info != null) {
        rb = info.getResponseBuilder();
      }

      if (fromIndex == null) {
        isSameCoreJoin = true;
      } else {
        if (info == null) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Cross-core join must have SolrRequestInfo");
        }

        CoreContainer container = searcher.getCore().getCoreContainer();
        final SolrCore fromCore = container.getCore(fromIndex);

        try {  
          if (fromCore == null) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Cross-core join: no such core " + fromIndex);
          }

          if (info.getReq().getCore() == fromCore) {
            isSameCoreJoin = true;
          } else {
            isSameCoreJoin = false;
          }
        } finally {
          fromCore.close();
        }
        info.addInitHook(new Callable<Object>() {
          @Override
          public void call(Object data) {
            if (!isSameCoreJoin ) {
              info.setInitData(container.getCore(fromIndex));
            }
          }
        });

        info.addCloseHook(new Closeable() {
          @Override
          public void close() {
            synchronized (info) {
              SolrCore fromCore = (SolrCore) info.getInitData();
              if (fromCore != null) {
                fromCore.close();
                info.setInitData(null); // unset
              }
            }
          }
        });

      }
      this.toSearcher = searcher;
    }

    DocSet resultSet;
    Filter filter;


    @Override
    public Scorer scorer(LeafReaderContext context) throws IOException {
      if (filter == null) {
        boolean debug = rb != null && rb.isDebug();
        RTimer timer = (debug ? new RTimer() : null);
        resultSet = getDocSet();
        if (timer != null) timer.stop();

        if (debug) {
          SimpleOrderedMap<Object> dbg = new SimpleOrderedMap<>();
          dbg.add("time", (long) timer.getTime());
          dbg.add("fromSetSize", fromSetSize);  // the input
          dbg.add("toSetSize", resultSet.size());    // the output

          dbg.add("fromTermCount", fromTermCount);
          dbg.add("fromTermTotalDf", fromTermTotalDf);
          dbg.add("fromTermDirectCount", fromTermDirectCount);
          dbg.add("fromTermHits", fromTermHits);
          dbg.add("fromTermHitsTotalDf", fromTermHitsTotalDf);
          dbg.add("toTermHits", toTermHits);
          dbg.add("toTermHitsTotalDf", toTermHitsTotalDf);
          dbg.add("toTermDirectCount", toTermDirectCount);
          dbg.add("smallSetsDeferred", smallSetsDeferred);
          dbg.add("toSetDocsAdded", resultListDocs);

          // TODO: perhaps synchronize  addDebug in the future...
          rb.addDebug(dbg, "join", JoinQuery.this.toString());
        }

        filter = resultSet.getTopFilter();
      }

      // Although this set only includes live docs, other filters can be pushed down to queries.
      DocIdSet readerSet = filter.getDocIdSet(context, null);
      if (readerSet == null) {
        return null;
      }
      DocIdSetIterator readerSetIterator = readerSet.iterator();
      if (readerSetIterator == null) {
        return null;
      }
      return new ConstantScoreScorer(this, score(), scoreMode, readerSetIterator);
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      return false;
    }

    // most of these statistics are only used for the enum method
    int fromSetSize;          // number of docs in the fromSet (that match the from query)
    long resultListDocs;      // total number of docs collected
    int fromTermCount;
    long fromTermTotalDf;
    int fromTermDirectCount;  // number of fromTerms that were too small to use the filter cache
    int fromTermHits;         // number of fromTerms that intersected the from query
    long fromTermHitsTotalDf; // sum of the df of the matching terms
    int toTermHits;           // num if intersecting from terms that match a term in the to field
    long toTermHitsTotalDf;   // sum of the df for the toTermHits
    int toTermDirectCount;    // number of toTerms that we set directly on a bitset rather than doing set intersections
    int smallSetsDeferred;    // number of small sets collected to be used later to intersect w/ bitset or create another small set


    public DocSet getDocSet() throws IOException {
      RefCounted<SolrIndexSearcher> fromSearcherRef = null;
      SolrIndexSearcher fromSearcher;
      SolrCore fromCore = null;
      boolean openedFromCoreHere = false;
      if (isSameCoreJoin) {
        // if this is the same core, use the searcher passed in... otherwise we could be warming and
        // get an older searcher from the core.
        fromSearcher = toSearcher;
      } else {
        fromCore = (SolrCore) SolrRequestInfo.getRequestInfo().getInitData();
        if (fromCore == null) {
          fromCore = toSearcher.getCore().getCoreContainer().getCore(fromIndex);
          openedFromCoreHere = true;
        }
        fromSearcherRef = fromCore.getSearcher();
        fromSearcher = fromSearcherRef.get();
      }
      try {
        SchemaField fromSchemaField = fromSearcher.getSchema().getField(fromField);
        SchemaField toSchemaField = toSearcher.getSchema().getField(toField);

        boolean usePoints = false;
        if (toSchemaField.getType().isPointField()) {
          if (!fromSchemaField.hasDocValues()) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "join from field " + fromSchemaField + " should have docValues to join with points field " + toSchemaField);
          }
          usePoints = true;
        }

        if (!usePoints) {
          return getDocSetEnumerate();
        }

        // point fields
        GraphPointsCollector collector = new GraphPointsCollector(fromSchemaField, null, null);
        fromSearcher.search(q, collector);
        Query resultQ = collector.getResultQuery(toSchemaField, false);
        // don't cache the resulting docSet... the query may be very large.  Better to cache the results of the join query itself
        DocSet result = resultQ==null ? DocSet.EMPTY : toSearcher.getDocSetNC(resultQ, null);
        return result;
      } finally {
        if (fromSearcherRef != null) {
          fromSearcherRef.decref();
        }
        if (fromCore != null && openedFromCoreHere) {
          fromCore.close();
        }
      }
    }



    public DocSet getDocSetEnumerate() throws IOException {
      RefCounted<SolrIndexSearcher> fromSearcherRef = null;
      SolrIndexSearcher fromSearcher;
      SolrCore fromCore = null;
      boolean openedFromCoreHere = false;
      if (isSameCoreJoin) {
        fromSearcher = toSearcher;
      } else {
        fromCore = (SolrCore) SolrRequestInfo.getRequestInfo().getInitData();
        if (fromCore == null) {
          fromCore = toSearcher.getCore().getCoreContainer().getCore(fromIndex);
          openedFromCoreHere = true;
        }
        fromSearcherRef = fromCore.getSearcher();
        fromSearcher = fromSearcherRef.get();
      }
      try {
        FixedBitSet resultBits = null;

        // minimum docFreq to use the cache
        int minDocFreqFrom = Math.max(5, fromSearcher.maxDoc() >> 13);
        int minDocFreqTo = Math.max(5, toSearcher.maxDoc() >> 13);

        // use a smaller size than normal since we will need to sort and dedup the results
        int maxSortedIntSize = Math.max(10, toSearcher.maxDoc() >> 10);

        DocSet fromSet = fromSearcher.getDocSet(q);
        fromSetSize = fromSet.size();

        List<DocSet> resultList = new ArrayList<>(10);

        // make sure we have a set that is fast for random access, if we will use it for that
        Bits fastForRandomSet;
        if (minDocFreqFrom <= 0) {
          fastForRandomSet = null;
        } else {
          fastForRandomSet = fromSet.getBits();
        }


        LeafReader fromReader = fromSearcher.getSlowAtomicReader();
        LeafReader toReader = fromSearcher==toSearcher ? fromReader : toSearcher.getSlowAtomicReader();
        Terms terms = fromReader.terms(fromField);
        Terms toTerms = toReader.terms(toField);
        if (terms == null || toTerms==null) return DocSet.EMPTY;
        String prefixStr = TrieField.getMainValuePrefix(fromSearcher.getSchema().getFieldType(fromField));
        BytesRef prefix = prefixStr == null ? null : new BytesRef(prefixStr);

        BytesRef term = null;
        TermsEnum  termsEnum = terms.iterator();
        TermsEnum  toTermsEnum = toTerms.iterator();
        SolrIndexSearcher.DocsEnumState fromDeState = null;
        SolrIndexSearcher.DocsEnumState toDeState = null;

        if (prefix == null) {
          term = termsEnum.next();
        } else {
          if (termsEnum.seekCeil(prefix) != TermsEnum.SeekStatus.END) {
            term = termsEnum.term();
          }
        }

        Bits fromLiveDocs = fromSearcher.getLiveDocsBits();
        Bits toLiveDocs = fromSearcher == toSearcher ? fromLiveDocs : toSearcher.getLiveDocsBits();

        fromDeState = new SolrIndexSearcher.DocsEnumState();
        fromDeState.fieldName = fromField;
        fromDeState.liveDocs = fromLiveDocs;
        fromDeState.termsEnum = termsEnum;
        fromDeState.postingsEnum = null;
        fromDeState.minSetSizeCached = minDocFreqFrom;

        toDeState = new SolrIndexSearcher.DocsEnumState();
        toDeState.fieldName = toField;
        toDeState.liveDocs = toLiveDocs;
        toDeState.termsEnum = toTermsEnum;
        toDeState.postingsEnum = null;
        toDeState.minSetSizeCached = minDocFreqTo;

        while (term != null) {
          if (prefix != null && !StringHelper.startsWith(term, prefix))
            break;

          fromTermCount++;

          boolean intersects = false;
          int freq = termsEnum.docFreq();
          fromTermTotalDf++;

          if (freq < minDocFreqFrom) {
            fromTermDirectCount++;
            // OK to skip liveDocs, since we check for intersection with docs matching query
            fromDeState.postingsEnum = fromDeState.termsEnum.postings(fromDeState.postingsEnum, PostingsEnum.NONE);
            PostingsEnum postingsEnum = fromDeState.postingsEnum;

            if (postingsEnum instanceof MultiPostingsEnum) {
              MultiPostingsEnum.EnumWithSlice[] subs = ((MultiPostingsEnum) postingsEnum).getSubs();
              int numSubs = ((MultiPostingsEnum) postingsEnum).getNumSubs();
              outer: for (int subindex = 0; subindex<numSubs; subindex++) {
                MultiPostingsEnum.EnumWithSlice sub = subs[subindex];
                if (sub.postingsEnum == null) continue;
                int base = sub.slice.start;
                int docid;
                while ((docid = sub.postingsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                  if (fastForRandomSet.get(docid+base)) {
                    intersects = true;
                    break outer;
                  }
                }
              }
            } else {
              int docid;
              while ((docid = postingsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (fastForRandomSet.get(docid)) {
                  intersects = true;
                  break;
                }
              }
            }
          } else {
            // use the filter cache
            DocSet fromTermSet = fromSearcher.getDocSet(fromDeState);
            intersects = fromSet.intersects(fromTermSet);
          }

          if (intersects) {
            fromTermHits++;
            fromTermHitsTotalDf++;
            TermsEnum.SeekStatus status = toTermsEnum.seekCeil(term);
            if (status == TermsEnum.SeekStatus.END) break;
            if (status == TermsEnum.SeekStatus.FOUND) {
              toTermHits++;
              int df = toTermsEnum.docFreq();
              toTermHitsTotalDf += df;
              if (resultBits==null && df + resultListDocs > maxSortedIntSize && resultList.size() > 0) {
                resultBits = new FixedBitSet(toSearcher.maxDoc());
              }

              // if we don't have a bitset yet, or if the resulting set will be too large
              // use the filterCache to get a DocSet
              if (toTermsEnum.docFreq() >= minDocFreqTo || resultBits == null) {
                // use filter cache
                DocSet toTermSet = toSearcher.getDocSet(toDeState);
                resultListDocs += toTermSet.size();
                if (resultBits != null) {
                  toTermSet.addAllTo(resultBits);
                } else {
                  if (toTermSet instanceof BitDocSet) {
                    resultBits = ((BitDocSet)toTermSet).getBits().clone();
                  } else {
                    resultList.add(toTermSet);
                  }
                }
              } else {
                toTermDirectCount++;

                // need to use liveDocs here so we don't map to any deleted ones
                toDeState.postingsEnum = toDeState.termsEnum.postings(toDeState.postingsEnum, PostingsEnum.NONE);
                toDeState.postingsEnum = BitsFilteredPostingsEnum.wrap(toDeState.postingsEnum, toDeState.liveDocs);
                PostingsEnum postingsEnum = toDeState.postingsEnum;

                if (postingsEnum instanceof MultiPostingsEnum) {
                  MultiPostingsEnum.EnumWithSlice[] subs = ((MultiPostingsEnum) postingsEnum).getSubs();
                  int numSubs = ((MultiPostingsEnum) postingsEnum).getNumSubs();
                  for (int subindex = 0; subindex<numSubs; subindex++) {
                    MultiPostingsEnum.EnumWithSlice sub = subs[subindex];
                    if (sub.postingsEnum == null) continue;
                    int base = sub.slice.start;
                    int docid;
                    while ((docid = sub.postingsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                      resultListDocs++;
                      resultBits.set(docid + base);
                    }
                  }
                } else {
                  int docid;
                  while ((docid = postingsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    resultListDocs++;
                    resultBits.set(docid);
                  }
                }
              }

            }
          }

          term = termsEnum.next();
        }

        smallSetsDeferred = resultList.size();

        if (resultBits != null) {
          for (DocSet set : resultList) {
            set.addAllTo(resultBits);
          }
          return new BitDocSet(resultBits);
        }

        if (resultList.size()==0) {
          return DocSet.EMPTY;
        }

        if (resultList.size() == 1) {
          return resultList.get(0);
        }

        int sz = 0;

        for (DocSet set : resultList)
          sz += set.size();

        int[] docs = new int[sz];
        int pos = 0;
        for (DocSet set : resultList) {
          System.arraycopy(((SortedIntDocSet)set).getDocs(), 0, docs, pos, set.size());
          pos += set.size();
        }
        Arrays.sort(docs);
        int[] dedup = new int[sz];
        pos = 0;
        int last = -1;
        for (int doc : docs) {
          if (doc != last)
            dedup[pos++] = doc;
          last = doc;
        }

        if (pos != dedup.length) {
          dedup = Arrays.copyOf(dedup, pos);
        }

        return new SortedIntDocSet(dedup, dedup.length);
      } finally {
        if (fromSearcherRef != null) {
          fromSearcherRef.decref();
        }
        if (fromCore != null && openedFromCoreHere) {
          fromCore.close();
        }
      }
    }

  }

  @Override
  public String toString(String field) {
    return "{!join from="+fromField+" to="+toField
        + (fromIndex != null ? " fromIndex="+fromIndex : "")
        +"}"+q.toString();
  }

  @Override
  public boolean equals(Object other) {
    return sameClassAs(other) &&
           equalsTo(getClass().cast(other));
  }

  private boolean equalsTo(JoinQuery other) {
    return this.fromField.equals(other.fromField)
        && this.toField.equals(other.toField)
        && this.q.equals(other.q)
        && Objects.equals(fromIndex, other.fromIndex)
        && this.fromCoreOpenTime == other.fromCoreOpenTime;
  }

  @Override
  public int hashCode() {
    int h = classHash();
    h = h * 31 + fromField.hashCode();
    h = h * 31 + toField.hashCode();
    h = h * 31 + q.hashCode();
    h = h * 31 + Objects.hashCode(fromIndex);
    h = h * 31 + (int) fromCoreOpenTime;
    return h;
  }
}
