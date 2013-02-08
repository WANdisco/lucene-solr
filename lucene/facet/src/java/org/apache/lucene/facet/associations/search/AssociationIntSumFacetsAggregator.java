package org.apache.lucene.facet.associations.search;

import java.io.IOException;

import org.apache.lucene.facet.index.params.CategoryListParams;
import org.apache.lucene.facet.search.FacetArrays;
import org.apache.lucene.facet.search.FacetsAggregator;
import org.apache.lucene.facet.search.FacetsCollector.MatchingDocs;

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

/**
 * A {@link FacetsAggregator} which computes the weight of a category as the sum
 * of the integer values associated with it in the result documents.
 */
public class AssociationIntSumFacetsAggregator implements FacetsAggregator {
  
  @Override
  public void aggregate(MatchingDocs matchingDocs, CategoryListParams clp,
      FacetArrays facetArrays) throws IOException {}
  
  @Override
  public void rollupValues(int ordinal, int[] children, int[] siblings,
      FacetArrays facetArrays) {}
  
  @Override
  public boolean requiresDocScores() {
    return false;
  }
  
}
