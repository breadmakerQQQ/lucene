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
package org.apache.lucene.index;


/**
 * @author Jacob.Hu
 *
 * doc_values六种类型
 */
/**
 * DocValues types. Note that DocValues is strongly typed, so a field cannot have different types
 * across different documents.
 */
public enum DocValuesType {

  // Jacob 该字段不存储doc_values, e.g. text类型或显式指定了不生成doc_values的字段
  /** No doc values for this field. */
  NONE,


  // Jacob 数值类型都使用此种类型doc_values, e.g. 整数/浮点数
  /** A per-document Number */
  NUMERIC,


  // Jacob 字节数组
  /**
   * A per-document byte[]. Values may be larger than 32766 bytes, but different codecs may enforce
   * their own limits.
   */
  BINARY,


  // Jacob 单值, keyword
  /**
   * A pre-sorted byte[]. Fields with this type only store distinct byte values and store an
   * additional offset pointer per document to dereference the shared byte[]. The stored byte[] is
   * presorted and allows access via document id, ordinal and by-value. Values must be {@code <=
   * 32766} bytes.
   */
  SORTED,


  // Jacob 多值, 数值
  /**
   * A pre-sorted Number[]. Fields with this type store numeric values in sorted order according to
   * {@link Long#compare(long, long)}.
   */
  SORTED_NUMERIC,


  // Jacob 多值, keyword
  /**
   * A pre-sorted Set&lt;byte[]&gt;. Fields with this type only store distinct byte values and store
   * additional offset pointers per document to dereference the shared byte[]s. The stored byte[] is
   * presorted and allows access via document id, ordinal and by-value. Values must be {@code <=
   * 32766} bytes.
   */
  SORTED_SET,
}
