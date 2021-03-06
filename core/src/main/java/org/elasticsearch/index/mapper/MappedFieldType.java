/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.queries.TermsQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.Version;
import org.elasticsearch.action.fieldstats.FieldStats;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.joda.DateMathParser;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.query.QueryShardException;
import org.elasticsearch.index.similarity.SimilarityProvider;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * This defines the core properties and functions to operate on a field.
 */
public abstract class MappedFieldType extends FieldType {

    private String name;
    private float boost;
    // TODO: remove this docvalues flag and use docValuesType
    private boolean docValues;
    private NamedAnalyzer indexAnalyzer;
    private NamedAnalyzer searchAnalyzer;
    private NamedAnalyzer searchQuoteAnalyzer;
    private SimilarityProvider similarity;
    private Object nullValue;
    private String nullValueAsString; // for sending null value to _all field
    private boolean eagerGlobalOrdinals;

    protected MappedFieldType(MappedFieldType ref) {
        super(ref);
        this.name = ref.name();
        this.boost = ref.boost();
        this.docValues = ref.hasDocValues();
        this.indexAnalyzer = ref.indexAnalyzer();
        this.searchAnalyzer = ref.searchAnalyzer();
        this.searchQuoteAnalyzer = ref.searchQuoteAnalyzer();
        this.similarity = ref.similarity();
        this.nullValue = ref.nullValue();
        this.nullValueAsString = ref.nullValueAsString();
        this.eagerGlobalOrdinals = ref.eagerGlobalOrdinals;
    }

    public MappedFieldType() {
        setTokenized(true);
        setStored(false);
        setStoreTermVectors(false);
        setOmitNorms(false);
        setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        setBoost(1.0f);
    }

    @Override
    public abstract MappedFieldType clone();

    /** Return a fielddata builder for this field. */
    public IndexFieldData.Builder fielddataBuilder() {
        throw new IllegalArgumentException("Fielddata is not supported on fields of type [" + typeName() + "]");
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) return false;
        MappedFieldType fieldType = (MappedFieldType) o;
        // check similarity first because we need to check the name, and it might be null
        // TODO: SimilarityProvider should have equals?
        if (similarity == null || fieldType.similarity == null) {
            if (similarity != fieldType.similarity) {
                return false;
            }
        } else {
            if (Objects.equals(similarity.name(), fieldType.similarity.name()) == false) {
                return false;
            }
        }

        return boost == fieldType.boost &&
            docValues == fieldType.docValues &&
            Objects.equals(name, fieldType.name) &&
            Objects.equals(indexAnalyzer, fieldType.indexAnalyzer) &&
            Objects.equals(searchAnalyzer, fieldType.searchAnalyzer) &&
            Objects.equals(searchQuoteAnalyzer(), fieldType.searchQuoteAnalyzer()) &&
            Objects.equals(eagerGlobalOrdinals, fieldType.eagerGlobalOrdinals) &&
            Objects.equals(nullValue, fieldType.nullValue) &&
            Objects.equals(nullValueAsString, fieldType.nullValueAsString);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), name, boost, docValues, indexAnalyzer, searchAnalyzer, searchQuoteAnalyzer,
            eagerGlobalOrdinals, similarity == null ? null : similarity.name(), nullValue, nullValueAsString);
    }

    // norelease: we need to override freeze() and add safety checks that all settings are actually set

    /** Returns the name of this type, as would be specified in mapping properties */
    public abstract String typeName();

    /** Checks this type is the same type as other. Adds a conflict if they are different. */
    private final void checkTypeName(MappedFieldType other) {
        if (typeName().equals(other.typeName()) == false) {
            throw new IllegalArgumentException("mapper [" + name + "] cannot be changed from type [" + typeName() + "] to [" + other.typeName() + "]");
        } else if (getClass() != other.getClass()) {
            throw new IllegalStateException("Type names equal for class " + getClass().getSimpleName() + " and " + other.getClass().getSimpleName());
        }
    }

    /**
     * Checks for any conflicts between this field type and other.
     * If strict is true, all properties must be equal.
     * Otherwise, only properties which must never change in an index are checked.
     */
    public void checkCompatibility(MappedFieldType other, List<String> conflicts, boolean strict) {
        checkTypeName(other);

        boolean indexed =  indexOptions() != IndexOptions.NONE;
        boolean mergeWithIndexed = other.indexOptions() != IndexOptions.NONE;
        // TODO: should be validating if index options go "up" (but "down" is ok)
        if (indexed != mergeWithIndexed || tokenized() != other.tokenized()) {
            conflicts.add("mapper [" + name() + "] has different [index] values");
        }
        if (stored() != other.stored()) {
            conflicts.add("mapper [" + name() + "] has different [store] values");
        }
        if (hasDocValues() != other.hasDocValues()) {
            conflicts.add("mapper [" + name() + "] has different [doc_values] values");
        }
        if (omitNorms() && !other.omitNorms()) {
            conflicts.add("mapper [" + name() + "] has different [norms] values, cannot change from disable to enabled");
        }
        if (storeTermVectors() != other.storeTermVectors()) {
            conflicts.add("mapper [" + name() + "] has different [store_term_vector] values");
        }
        if (storeTermVectorOffsets() != other.storeTermVectorOffsets()) {
            conflicts.add("mapper [" + name() + "] has different [store_term_vector_offsets] values");
        }
        if (storeTermVectorPositions() != other.storeTermVectorPositions()) {
            conflicts.add("mapper [" + name() + "] has different [store_term_vector_positions] values");
        }
        if (storeTermVectorPayloads() != other.storeTermVectorPayloads()) {
            conflicts.add("mapper [" + name() + "] has different [store_term_vector_payloads] values");
        }

        // null and "default"-named index analyzers both mean the default is used
        if (indexAnalyzer() == null || "default".equals(indexAnalyzer().name())) {
            if (other.indexAnalyzer() != null && "default".equals(other.indexAnalyzer().name()) == false) {
                conflicts.add("mapper [" + name() + "] has different [analyzer]");
            }
        } else if (other.indexAnalyzer() == null || "default".equals(other.indexAnalyzer().name())) {
            conflicts.add("mapper [" + name() + "] has different [analyzer]");
        } else if (indexAnalyzer().name().equals(other.indexAnalyzer().name()) == false) {
            conflicts.add("mapper [" + name() + "] has different [analyzer]");
        }

        if (Objects.equals(similarity(), other.similarity()) == false) {
            conflicts.add("mapper [" + name() + "] has different [similarity]");
        }

        if (strict) {
            if (omitNorms() != other.omitNorms()) {
                conflicts.add("mapper [" + name() + "] is used by multiple types. Set update_all_types to true to update [omit_norms] across all types.");
            }
            if (boost() != other.boost()) {
                conflicts.add("mapper [" + name() + "] is used by multiple types. Set update_all_types to true to update [boost] across all types.");
            }
            if (Objects.equals(searchAnalyzer(), other.searchAnalyzer()) == false) {
                conflicts.add("mapper [" + name() + "] is used by multiple types. Set update_all_types to true to update [search_analyzer] across all types.");
            }
            if (Objects.equals(searchQuoteAnalyzer(), other.searchQuoteAnalyzer()) == false) {
                conflicts.add("mapper [" + name() + "] is used by multiple types. Set update_all_types to true to update [search_quote_analyzer] across all types.");
            }
            if (Objects.equals(nullValue(), other.nullValue()) == false) {
                conflicts.add("mapper [" + name() + "] is used by multiple types. Set update_all_types to true to update [null_value] across all types.");
            }
            if (eagerGlobalOrdinals() != other.eagerGlobalOrdinals()) {
                conflicts.add("mapper [" + name() + "] is used by multiple types. Set update_all_types to true to update [eager_global_ordinals] across all types.");
            }
        }
    }

    public boolean isNumeric() {
        return false;
    }

    public boolean isSortable() {
        return true;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        checkIfFrozen();
        this.name = name;
    }

    public float boost() {
        return boost;
    }

    public void setBoost(float boost) {
        checkIfFrozen();
        this.boost = boost;
    }

    public boolean hasDocValues() {
        return docValues;
    }

    public void setHasDocValues(boolean hasDocValues) {
        checkIfFrozen();
        this.docValues = hasDocValues;
    }

    public NamedAnalyzer indexAnalyzer() {
        return indexAnalyzer;
    }

    public void setIndexAnalyzer(NamedAnalyzer analyzer) {
        checkIfFrozen();
        this.indexAnalyzer = analyzer;
    }

    public NamedAnalyzer searchAnalyzer() {
        return searchAnalyzer;
    }

    public void setSearchAnalyzer(NamedAnalyzer analyzer) {
        checkIfFrozen();
        this.searchAnalyzer = analyzer;
    }

    public NamedAnalyzer searchQuoteAnalyzer() {
        return searchQuoteAnalyzer == null ? searchAnalyzer : searchQuoteAnalyzer;
    }

    public void setSearchQuoteAnalyzer(NamedAnalyzer analyzer) {
        checkIfFrozen();
        this.searchQuoteAnalyzer = analyzer;
    }

    public SimilarityProvider similarity() {
        return similarity;
    }

    public void setSimilarity(SimilarityProvider similarity) {
        checkIfFrozen();
        this.similarity = similarity;
    }

    /** Returns the value that should be added when JSON null is found, or null if no value should be added */
    public Object nullValue() {
        return nullValue;
    }

    /** Returns the null value stringified, so it can be used for e.g. _all field, or null if there is no null value */
    public String nullValueAsString() {
        return nullValueAsString;
    }

    /** Sets the null value and initializes the string version */
    public void setNullValue(Object nullValue) {
        checkIfFrozen();
        this.nullValue = nullValue;
        this.nullValueAsString = nullValue == null ? null : nullValue.toString();
    }

    /** Returns the actual value of the field. */
    public Object value(Object value) {
        return value;
    }

    /** Returns the value that will be used as a result for search. Can be only of specific types... */
    public Object valueForSearch(Object value) {
        return value;
    }

    /** Returns the indexed value used to construct search "values". */
    public BytesRef indexedValueForSearch(Object value) {
        return BytesRefs.toBytesRef(value);
    }

    /**
     * Should the field query {@link #termQuery(Object, org.elasticsearch.index.query.QueryShardContext)}  be used when detecting this
     * field in query string.
     */
    public boolean useTermQueryWithQueryString() {
        return false;
    }

    /**
     * Creates a term associated with the field of this mapper for the given
     * value. Its important to use termQuery when building term queries because
     * things like ParentFieldMapper override it to make more interesting
     * queries.
     */
    protected Term createTerm(Object value) {
        return new Term(name(), indexedValueForSearch(value));
    }

    public Query termQuery(Object value, @Nullable QueryShardContext context) {
        TermQuery query = new TermQuery(createTerm(value));
        if (boost == 1f ||
            (context != null && context.indexVersionCreated().before(Version.V_5_0_0_alpha1))) {
            return query;
        }
        return new BoostQuery(query, boost);
    }

    public Query termsQuery(List values, @Nullable QueryShardContext context) {
        BytesRef[] bytesRefs = new BytesRef[values.size()];
        for (int i = 0; i < bytesRefs.length; i++) {
            bytesRefs[i] = indexedValueForSearch(values.get(i));
        }
        return new TermsQuery(name(), bytesRefs);
    }

    public Query rangeQuery(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper) {
        return new TermRangeQuery(name(),
            lowerTerm == null ? null : indexedValueForSearch(lowerTerm),
            upperTerm == null ? null : indexedValueForSearch(upperTerm),
            includeLower, includeUpper);
    }

    public Query fuzzyQuery(Object value, Fuzziness fuzziness, int prefixLength, int maxExpansions, boolean transpositions) {
        return new FuzzyQuery(createTerm(value), fuzziness.asDistance(BytesRefs.toString(value)), prefixLength, maxExpansions, transpositions);
    }

    public Query prefixQuery(String value, @Nullable MultiTermQuery.RewriteMethod method, @Nullable QueryShardContext context) {
        PrefixQuery query = new PrefixQuery(createTerm(value));
        if (method != null) {
            query.setRewriteMethod(method);
        }
        return query;
    }

    public Query regexpQuery(String value, int flags, int maxDeterminizedStates, @Nullable MultiTermQuery.RewriteMethod method, @Nullable QueryShardContext context) {
        if (numericType() != null) {
            throw new QueryShardException(context, "Cannot use regular expression to filter numeric field [" + name + "]");
        }

        RegexpQuery query = new RegexpQuery(createTerm(value), flags, maxDeterminizedStates);
        if (method != null) {
            query.setRewriteMethod(method);
        }
        return query;
    }

    public Query nullValueQuery() {
        if (nullValue == null) {
            return null;
        }
        return new ConstantScoreQuery(termQuery(nullValue, null));
    }

    /**
     * @return a {@link FieldStats} instance that maps to the type of this
     * field or {@code null} if the provided index has no stats about the
     * current field
     */
    public FieldStats stats(IndexReader reader) throws IOException {
        int maxDoc = reader.maxDoc();
        Terms terms = MultiFields.getTerms(reader, name());
        if (terms == null) {
            return null;
        }
        return new FieldStats.Text(
            maxDoc, terms.getDocCount(), terms.getSumDocFreq(), terms.getSumTotalTermFreq(), terms.getMin(), terms.getMax()
        );
    }

    /**
     * An enum used to describe the relation between the range of terms in a
     * shard when compared with a query range
     */
    public static enum Relation {
        WITHIN,
        INTERSECTS,
        DISJOINT;
    }

    /** Return whether all values of the given {@link IndexReader} are within the range,
     *  outside the range or cross the range. The default implementation returns
     *  {@link Relation#INTERSECTS}, which is always fine to return when there is
     *  no way to check whether values are actually within bounds. */
    public Relation isFieldWithinQuery(
            IndexReader reader,
            Object from, Object to,
            boolean includeLower, boolean includeUpper,
            DateTimeZone timeZone, DateMathParser dateMathParser) throws IOException {
        return Relation.INTERSECTS;
    }

    /** A term query to use when parsing a query string. Can return <tt>null</tt>. */
    @Nullable
    public Query queryStringTermQuery(Term term) {
        return null;
    }

    protected final void failIfNoDocValues() {
        if (hasDocValues() == false) {
            throw new IllegalStateException("Can't load fielddata on [" + name()
                + "] because fielddata is unsupported on fields of type ["
                + typeName() + "]. Use doc values instead.");
        }
    }

    public boolean eagerGlobalOrdinals() {
        return eagerGlobalOrdinals;
    }

    public void setEagerGlobalOrdinals(boolean eagerGlobalOrdinals) {
        checkIfFrozen();
        this.eagerGlobalOrdinals = eagerGlobalOrdinals;
    }

}
