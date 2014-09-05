package org.apache.solr.handler.component;

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

import org.apache.lucene.util.BytesRef;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.schema.FieldType;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.request.SimpleFacets;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.lucene.search.Query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Processes all Pivot facet logic for a single node -- both non-distrib, and per-shard
 */
public class PivotFacetProcessor extends SimpleFacets
{
  protected SolrParams params;
    
  public PivotFacetProcessor(SolrQueryRequest req, DocSet docs, SolrParams params, ResponseBuilder rb) {
    super(req, docs, params, rb);
    this.params = params;
  }
  
  /**
   * Processes all of the specified {@link FacetParams#FACET_PIVOT} strings, generating 
   * a completel response tree for each pivot.  The values in this response will either 
   * be the complete tree of fields and values for the specified pivot in the local index, 
   * or the requested refinements if the pivot params include the {@link PivotFacet#REFINE_PARAM}
   */
  public SimpleOrderedMap<List<NamedList<Object>>> process(String[] pivots) throws IOException {
    if (!rb.doFacets || pivots == null) 
      return null;
    
    SimpleOrderedMap<List<NamedList<Object>>> pivotResponse = new SimpleOrderedMap<List<NamedList<Object>>>();
    for (String pivotList : pivots) {
      try {
        this.parseParams(FacetParams.FACET_PIVOT, pivotList);
      } catch (SyntaxError e) {
        throw new SolrException(ErrorCode.BAD_REQUEST, e);
      }
      List<String> pivotFields = StrUtils.splitSmart(facetValue, ",", true);
      if( pivotFields.size() < 1 ) {
        throw new SolrException( ErrorCode.BAD_REQUEST,
                                 "Pivot Facet needs at least one field name: " + pivotList);
      } else {
        SolrIndexSearcher searcher = rb.req.getSearcher();
        for (String fieldName : pivotFields) {
          SchemaField sfield = searcher.getSchema().getField(fieldName);
          if (sfield == null) {
            throw new SolrException(ErrorCode.BAD_REQUEST, "\"" + fieldName + "\" is not a valid field name in pivot: " + pivotList);
          }
        }
      } 

      //REFINEMENT
      String fieldValueKey = localParams == null ? null : localParams.get(PivotFacet.REFINE_PARAM);
      if(fieldValueKey != null ){
        String[] refinementValuesByField = params.getParams(PivotFacet.REFINE_PARAM+fieldValueKey);
        for(String refinements : refinementValuesByField){
          pivotResponse.addAll(processSingle(pivotFields, refinements));
        }
      } else{
        pivotResponse.addAll(processSingle(pivotFields, null));
      }
    }
    return pivotResponse;
  }

  /**
   * Process a single branch of refinement values for a specific pivot
   * @param pivotFields the ordered list of fields in this pivot
   * @param refinements the comma seperate list of refinement values corrisponding to each field in the pivot, or null if there are no refinements
   */
  private SimpleOrderedMap<List<NamedList<Object>>> processSingle(List<String> pivotFields,
                                                                  String refinements) throws IOException {
    SolrIndexSearcher searcher = rb.req.getSearcher();
    SimpleOrderedMap<List<NamedList<Object>>> pivotResponse = new SimpleOrderedMap<List<NamedList<Object>>>();

    String field = pivotFields.get(0);
    SchemaField sfield = searcher.getSchema().getField(field);
      
    Deque<String> fnames = new LinkedList<String>();
    for( int i = pivotFields.size()-1; i>1; i-- ) {
      fnames.push( pivotFields.get(i) );
    }
    
    NamedList<Integer> facetCounts;
    Deque<String> vnames = new LinkedList<String>();

    if (null != refinements) {
      // All values, split by the field they should go to
      List<String> refinementValuesByField
        = PivotFacetHelper.decodeRefinementValuePath(refinements);

      for( int i=refinementValuesByField.size()-1; i>0; i-- ) {
        vnames.push(refinementValuesByField.get(i));//Only for [1] and on
      }

      String firstFieldsValues = refinementValuesByField.get(0);

      facetCounts = new NamedList<Integer>();
      facetCounts.add(firstFieldsValues,
                      getSubsetSize(this.docs, sfield, firstFieldsValues));
    } else {
      // no refinements needed
      facetCounts = this.getTermCountsForPivots(field, this.docs);
    }
    
    if(pivotFields.size() > 1) {
      String subField = pivotFields.get(1);
      pivotResponse.add(key,
                        doPivots(facetCounts, field, subField, fnames, vnames, this.docs));
    } else {
      pivotResponse.add(key, doPivots(facetCounts, field, null, fnames, vnames, this.docs));
    }
    return pivotResponse;
  }
  
  /**
   * Recursive function to compute all the pivot counts for the values under teh specified field
   */
  protected List<NamedList<Object>> doPivots(NamedList<Integer> superFacets,
      String field, String subField, Deque<String> fnames,Deque<String> vnames,DocSet docs) throws IOException {

    SolrIndexSearcher searcher = rb.req.getSearcher();
    // TODO: optimize to avoid converting to an external string and then having to convert back to internal below
    SchemaField sfield = searcher.getSchema().getField(field);
    FieldType ftype = sfield.getType();

    String nextField = fnames.poll();

    // re-useable BytesRefBuilder for conversion of term values to Objects
    BytesRef termval = new BytesRef(); 

    List<NamedList<Object>> values = new ArrayList<NamedList<Object>>( superFacets.size() );
    for (Map.Entry<String, Integer> kv : superFacets) {
      // Only sub-facet if parent facet has positive count - still may not be any values for the sub-field though
      if (kv.getValue() >= getMinCountForField(field)) {  
        final String fieldValue = kv.getKey();

        SimpleOrderedMap<Object> pivot = new SimpleOrderedMap<Object>();
        pivot.add( "field", field );
        if (null == fieldValue) {
          pivot.add( "value", null );
        } else {
          ftype.readableToIndexed(fieldValue, termval);
          pivot.add( "value", ftype.toObject(sfield, termval) );
        }
        pivot.add( "count", kv.getValue() );

        DocSet subset = getSubset(docs, sfield, fieldValue);
        
        if( subField != null )  {
          NamedList<Integer> facetCounts;
          if(!vnames.isEmpty()){
            String val = vnames.pop();
            facetCounts = new NamedList<Integer>();
            facetCounts.add(val, getSubsetSize(subset,
                                               searcher.getSchema().getField(subField),
                                               val));
          } else {
            facetCounts = this.getTermCountsForPivots(subField, subset);
          }

          if (facetCounts.size() >= 1) {
            pivot.add( "pivot", doPivots( facetCounts, subField, nextField, fnames, vnames, subset) );
          }
        }
        values.add( pivot );
      }

    }
    // put the field back on the list
    fnames.push( nextField );
    return values;
  }
  
  /**
   * Given a base docset, computes the size of the subset of documents corrisponding to the specified pivotValue
   *
   * @param base the set of documents to evalute relative to
   * @param field the field type used by the pivotValue
   * @param pivotValue String representation of the value, may be null (ie: "missing")
   */
  private int getSubsetSize(DocSet base, SchemaField field, String pivotValue) throws IOException {
    FieldType ft = field.getType();
    if ( null == pivotValue ) {
      Query query = ft.getRangeQuery(null, field, null, null, false, false);
      DocSet hasVal = searcher.getDocSet(query);
      return base.andNotSize(hasVal);
    } else {
      Query query = ft.getFieldQuery(null, field, pivotValue);
      return searcher.numDocs(query, base);
    }
  }

  /**
   * Given a base docset, computes the subset of documents corrisponding to the specified pivotValue
   *
   * @param base the set of documents to evalute relative to
   * @param field the field type used by the pivotValue
   * @param pivotValue String representation of the value, may be null (ie: "missing")
   */
  private DocSet getSubset(DocSet base, SchemaField field, String pivotValue) throws IOException {
    FieldType ft = field.getType();
    if ( null == pivotValue ) {
      Query query = ft.getRangeQuery(null, field, null, null, false, false);
      DocSet hasVal = searcher.getDocSet(query);
      return base.andNot(hasVal);
    } else {
      Query query = ft.getFieldQuery(null, field, pivotValue);
      return searcher.getDocSet(query, base);
    }
  }

  private int getMinCountForField(String fieldname){
    return params.getFieldInt(fieldname, FacetParams.FACET_PIVOT_MINCOUNT, 1);
  }
  
}
