/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gaffer.accumulostore.operation.spark.handler;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import gaffer.accumulostore.AccumuloStore;
import gaffer.data.element.Edge;
import gaffer.data.element.Element;
import gaffer.data.element.ElementComponentKey;
import gaffer.data.element.Entity;
import gaffer.data.elementdefinition.view.View;
import gaffer.data.elementdefinition.view.ViewElementDefinition;
import gaffer.function.FilterFunction;
import gaffer.function.context.ConsumerFunctionContext;
import gaffer.function.simple.filter.Exists;
import gaffer.function.simple.filter.IsEqual;
import gaffer.function.simple.filter.IsIn;
import gaffer.function.simple.filter.IsLessThan;
import gaffer.function.simple.filter.IsMoreThan;
import gaffer.function.simple.filter.Not;
import gaffer.operation.OperationException;
import gaffer.operation.data.EntitySeed;
import gaffer.operation.simple.spark.AbstractGetRDD;
import gaffer.operation.simple.spark.ConversionException;
import gaffer.operation.simple.spark.Converter;
import gaffer.operation.simple.spark.GetRDDOfAllElements;
import gaffer.operation.simple.spark.GetRDDOfElements;
import gaffer.store.schema.SchemaEdgeDefinition;
import gaffer.store.schema.SchemaElementDefinition;
import gaffer.store.schema.SchemaEntityDefinition;
import gaffer.user.User;
import org.apache.commons.lang.StringUtils;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.Row$;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.sources.And;
import org.apache.spark.sql.sources.BaseRelation;
import org.apache.spark.sql.sources.EqualNullSafe;
import org.apache.spark.sql.sources.EqualTo;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.sources.GreaterThan;
import org.apache.spark.sql.sources.GreaterThanOrEqual;
import org.apache.spark.sql.sources.In;
import org.apache.spark.sql.sources.IsNotNull;
import org.apache.spark.sql.sources.IsNull;
import org.apache.spark.sql.sources.LessThan;
import org.apache.spark.sql.sources.LessThanOrEqual;
import org.apache.spark.sql.sources.PrunedFilteredScan;
import org.apache.spark.sql.sources.PrunedScan;
import org.apache.spark.sql.sources.TableScan;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.runtime.AbstractFunction1;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allows Apache Spark to retrieve data from an {@link AccumuloStore} as a <code>DataFrame</code>. Spark's Java API
 * does not expose the <code>DataFrame</code> class, but it is just a type alias for a {@link
 * org.apache.spark.sql.Dataset} of {@link Row}s. The schema of the <code>DataFrame</code> is formed from the schemas
 * of the groups specified in the view.
 * <p>
 * If two of the specified groups have properties with the same name, then the types of those properties must be
 * the same.
 * <p>
 * <code>AccumuloStoreRelation</code> implements the {@link TableScan} interface which allows all {@link Element}s to
 * of the specified groups to be returned to the <code>DataFrame</code>.
 * <p>
 * <code>AccumuloStoreRelation</code> implements the {@link PrunedScan} interface which allows all {@link Element}s
 * of the specified groups to be returned to the <code>DataFrame</code> but with only the specified columns returned.
 * Currently, {@link AccumuloStore} does not allow projection of properties in the tablet server, so this projection
 * is performed within the Spark executors, rather than in Accumulo's tablet servers. Once {@link AccumuloStore}
 * supports this projection in the tablet servers, then this will become more efficient.
 * <p>
 * <code>AccumuloStoreRelation</code> implements the {@link PrunedFilteredScan} interface which allows only
 * {@link Element}s that match the the provided {@link Filter}s to be returned. The majority of these are implemented
 * by adding them to the {@link View}, which causes them to be applied on Accumulo's tablet server (i.e. before
 * the data is sent to a Spark executor). If a {@link Filter} is specified that specifies either the vertex in an
 * {@link Entity} or either the source or destination vertex in an {@link Edge} then this is applied by using the
 * appropriate range scan on Accumulo. Queries against this <code>DataFrame</code> that do this should be very
 * quick.
 */
public class AccumuloStoreRelation extends BaseRelation implements TableScan, PrunedScan, PrunedFilteredScan {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccumuloStoreRelation.class);
    private static final List<Converter> DEFAULT_CONVERTERS = new ArrayList<>();
    static {
        DEFAULT_CONVERTERS.add(new FreqMapConverter());
        DEFAULT_CONVERTERS.add(new HyperLogLogPlusConverter());
    }

    private enum EntityOrEdge {
        ENTITY, EDGE
    }

    public static final String GROUP = "group";
    public static final String VERTEX_COL_NAME = "vertex";
    public static final String SRC_COL_NAME = "src";
    public static final String DST_COL_NAME = "dst";

    private final SQLContext sqlContext;
    private final LinkedHashSet<String> groups;
    private final List<Converter> converters;
    private final View view;
    private final AccumuloStore store;
    private final User user;
    private final Map<String, EntityOrEdge> entityOrEdgeByGroup = new HashMap<>();
    private final LinkedHashSet<String> usedProperties = new LinkedHashSet<>();
    private final Map<String, StructType> structTypeByGroup = new HashMap<>();
    private final Map<String, Boolean> propertyNeedsConversion = new HashMap<>();
    private final Map<String, Converter> converterByProperty = new HashMap<>();
    private StructType structType;

    public AccumuloStoreRelation(final SQLContext sqlContext,
                                 final List<Converter> converters,
                                 final View view,
                                 final AccumuloStore store,
                                 final User user) {
        this.sqlContext = sqlContext;
        this.converters = new ArrayList<>();
        this.converters.addAll(DEFAULT_CONVERTERS);
        if (converters != null) {
            this.converters.addAll(converters);
        }
        this.view = view;
        this.groups = new LinkedHashSet<>();
        for (final String group : view.getEntityGroups()) {
            groups.add(group);
        }
        for (final String group : view.getEdgeGroups()) {
            groups.add(group);
        }
        this.store = store;
        this.user = user;
        buildSchema();
    }

    @Override
    public SQLContext sqlContext() {
        return sqlContext;
    }

    @Override
    public StructType schema() {
        return structType;
    }

    /**
     * Creates a <code>DataFrame</code> of all {@link Element}s from the specified groups.
     *
     * @return An {@link RDD} of {@link Row}s containing {@link Element}s whose group is in <code>groups</code>.
     */
    @Override
    public RDD<Row> buildScan() {
        try {
            LOGGER.info("Building GetRDDOfAllElements with view set to groups {}", StringUtils.join(groups, ','));
            final GetRDDOfAllElements operation = new GetRDDOfAllElements(sqlContext.sparkContext());
            operation.setView(view);
            final RDD<Element> rdd = store.execute(operation, user);
            return rdd.map(new ElementToRow(usedProperties, propertyNeedsConversion, converterByProperty),
                    ClassTagConstants.ROW_CLASS_TAG);
        } catch (final OperationException e) {
            LOGGER.error("OperationException while executing operation: {}", e);
            return null;
        }
    }

    /**
     * Creates a <code>DataFrame</code> of all {@link Element}s from the specified groups with columns that are not
     * required filtered out.
     * <p>
     * Currently this does not push the projection down to the store (i.e. it should be implemented in an iterator,
     * not in the transform). Issue 320 refers to this.
     *
     * @param requiredColumns The columns to return.
     * @return An {@link RDD} of {@link Row}s containing the requested columns.
     */
    @Override
    public RDD<Row> buildScan(final String[] requiredColumns) {
        try {
            LOGGER.info("Building scan with required columns: {}", StringUtils.join(requiredColumns, ','));
            LOGGER.info("Building GetRDDOfAllElements with view set to groups {}", StringUtils.join(groups, ','));
            final GetRDDOfAllElements operation = new GetRDDOfAllElements(sqlContext.sparkContext());
            operation.setView(view);
            final RDD<Element> rdd = store.execute(operation, user);
            return rdd.map(new ElementToRow(new LinkedHashSet<>(Arrays.asList(requiredColumns)),
                            propertyNeedsConversion, converterByProperty),
                    ClassTagConstants.ROW_CLASS_TAG);
        } catch (final OperationException e) {
            LOGGER.error("OperationException while executing operation {}", e);
            return null;
        }
    }

    /**
     * Creates a <code>DataFrame</code> of all {@link Element}s from the specified groups with columns that are not
     * required filtered out and with (some of) the supplied {@link Filter}s applied.
     * <p>
     * Note that Spark also applies the provided {@link Filter}s - applying them here is an optimisation to reduce
     * the amount of data transferred from the store to Spark's executors (this is known as "predicate pushdown").
     * <p>
     * Currently this does not push the projection down to the store (i.e. it should be implemented in an iterator,
     * not in the transform). Issue 320 refers to this.
     *
     * @param requiredColumns The columns to return.
     * @param filters         The {@link Filter}s to apply (these are applied before aggregation).
     * @return An {@link RDD} of {@link Row}s containing the requested columns.
     */
    @Override
    public RDD<Row> buildScan(final String[] requiredColumns, final Filter[] filters) {
        LOGGER.info("Building scan with required columns {} and {} filters ({})",
                StringUtils.join(requiredColumns, ','),
                filters.length,
                StringUtils.join(filters, ','));
        // If any of the filters can be translated into Accumulo queries (i.e. specifying ranges rather than a full
        // table scan) then do this.
        AbstractGetRDD<?> operation = null;
        for (final Filter filter : filters) {
            if (filter instanceof EqualTo) {
                final EqualTo equalTo = (EqualTo) filter;
                final String attribute = equalTo.attribute();
                if (attribute.equals(SRC_COL_NAME) || attribute.equals(DST_COL_NAME) || attribute.equals(VERTEX_COL_NAME)) {
                    LOGGER.debug("Found EqualTo filter with attribute {}, creating GetRDDOfElements", attribute);
                    operation = new GetRDDOfElements<>(sqlContext.sparkContext(), new EntitySeed(equalTo.value()));
                }
                break;
            }
        }
        if (operation == null) {
            LOGGER.debug("Creating GetRDDOfAllElements");
            operation = new GetRDDOfAllElements(sqlContext.sparkContext());
        }
        // Create view based on filters and add to operation
        final List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> filterList = new ArrayList<>();
        for (final Filter filter : filters) {
            filterList.addAll(getFunctionsFromFilter(filter));
        }
        View.Builder builder = new View.Builder();
        for (final String group : view.getEntityGroups()) {
            final ViewElementDefinition ved = view.getEntity(group);
            ved.addPostAggregationElementFilterFunctions(filterList);
            builder = builder.entity(group, ved);
        }
        for (final String group : view.getEdgeGroups()) {
            final ViewElementDefinition ved = view.getEdge(group);
            ved.addPostAggregationElementFilterFunctions(filterList);
            builder = builder.edge(group, ved);
        }
        operation.setView(builder.build());
        // Create RDD
        try {
            final RDD<Element> rdd = store.execute(operation, user);
            return rdd.map(new ElementToRow(new LinkedHashSet<>(Arrays.asList(requiredColumns)),
                            propertyNeedsConversion, converterByProperty),
                    ClassTagConstants.ROW_CLASS_TAG);
        } catch (final OperationException e) {
            LOGGER.error("OperationException while executing operation {}", e);
            return null;
        }
    }

    /**
     * Converts a Spark {@link Filter} to a Gaffer {@link ConsumerFunctionContext}.
     * <p>
     * Note that Spark also applies all the filters provided to the {@link #buildScan(String[], Filter[])} method so
     * not implementing some of the provided {@link Filter}s in Gaffer will not cause errors. However, as many as
     * possible should be implemented so that as much filtering as possible happens in iterators running in Accumulo's
     * tablet servers (this avoids unnecessary data transfer from Accumulo to Spark).
     *
     * @param filter The {@link Filter} to transform.
     * @return Gaffer function(s) implementing the provided {@link Filter}.
     */
    private List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> getFunctionsFromFilter(final Filter filter) {
        final List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> functions = new ArrayList<>();
        if (filter instanceof EqualTo) {
            // Not dealt with as requires a FilterFunction that returns null if either the controlValue or the
            // test value is null - the API of FilterFunction doesn't permit this.
        } else if (filter instanceof EqualNullSafe) {
            final EqualNullSafe equalNullSafe = (EqualNullSafe) filter;
            final FilterFunction isEqual = new IsEqual(equalNullSafe.value());
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(equalNullSafe.attribute()));
            functions.add(new ConsumerFunctionContext<>(isEqual, ecks));
            LOGGER.debug("Converted {} to IsEqual ({})", filter, ecks.get(0));
        } else if (filter instanceof GreaterThan) {
            final GreaterThan greaterThan = (GreaterThan) filter;
            final FilterFunction isMoreThan = new IsMoreThan((Comparable<?>) greaterThan.value(), false);
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(greaterThan.attribute()));
            functions.add(new ConsumerFunctionContext<>(isMoreThan, ecks));
            LOGGER.debug("Converted {} to isMoreThan ({})", filter, ecks.get(0));
        } else if (filter instanceof GreaterThanOrEqual) {
            final GreaterThanOrEqual greaterThan = (GreaterThanOrEqual) filter;
            final FilterFunction isMoreThan = new IsMoreThan((Comparable<?>) greaterThan.value(), true);
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(greaterThan.attribute()));
            functions.add(new ConsumerFunctionContext<>(isMoreThan, ecks));
            LOGGER.debug("Converted {} to IsMoreThan ({})", filter, ecks.get(0));
        } else if (filter instanceof LessThan) {
            final LessThan lessThan = (LessThan) filter;
            final FilterFunction isLessThan = new IsLessThan((Comparable<?>) lessThan.value(), false);
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(lessThan.attribute()));
            functions.add(new ConsumerFunctionContext<>(isLessThan, ecks));
            LOGGER.debug("Converted {} to IsLessThan ({})", filter, ecks.get(0));
        } else if (filter instanceof LessThanOrEqual) {
            final LessThanOrEqual lessThan = (LessThanOrEqual) filter;
            final FilterFunction isLessThan = new IsLessThan((Comparable<?>) lessThan.value(), true);
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(lessThan.attribute()));
            functions.add(new ConsumerFunctionContext<>(isLessThan, ecks));
            LOGGER.debug("Converted {} to LessThanOrEqual ({})", filter, ecks.get(0));
        } else if (filter instanceof In) {
            final In in = (In) filter;
            final FilterFunction isIn = new IsIn(new HashSet<>(Arrays.asList(in.values())));
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(in.attribute()));
            functions.add(new ConsumerFunctionContext<>(isIn, ecks));
            LOGGER.debug("Converted {} to IsIn ({})", filter, ecks.get(0));
        } else if (filter instanceof IsNull) {
            final IsNull isNull = (IsNull) filter;
            final FilterFunction doesntExist = new Not(new Exists());
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(isNull.attribute()));
            functions.add(new ConsumerFunctionContext<>(doesntExist, ecks));
            LOGGER.debug("Converted {} to Not(Exists) ({})", filter, ecks.get(0));
        } else if (filter instanceof IsNotNull) {
            final IsNotNull isNotNull = (IsNotNull) filter;
            final FilterFunction exists = new Exists();
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(isNotNull.attribute()));
            functions.add(new ConsumerFunctionContext<>(exists, ecks));
            LOGGER.debug("Converted {} to Exists ({})", filter, ecks.get(0));
        } else if (filter instanceof And) {
            final And and = (And) filter;
            final List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> left = getFunctionsFromFilter(and.left());
            final List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> right = getFunctionsFromFilter(and.right());
            functions.addAll(left);
            functions.addAll(right);
            LOGGER.debug("Converted {} to 2 filters ({} and {})", filter, StringUtils.join(left, ','), StringUtils.join(right, ','));
        }
        return functions;
    }

    private void buildSchema() {
        LOGGER.info("Building Spark SQL schema for groups {}", StringUtils.join(groups, ','));
        for (final String group : groups) {
            final SchemaElementDefinition elementDefn = store.getSchema().getElement(group);
            final List<StructField> structFieldList = new ArrayList<>();
            if (elementDefn instanceof SchemaEntityDefinition) {
                entityOrEdgeByGroup.put(group, EntityOrEdge.ENTITY);
                final SchemaEntityDefinition entityDefinition = (SchemaEntityDefinition) elementDefn;
                final String vertexClass = store.getSchema().getType(entityDefinition.getVertex()).getClassString();
                final DataType vertexType = getType(vertexClass);
                if (vertexType == null) {
                    throw new RuntimeException("Vertex must be a recognised type: found " + vertexClass);
                }
                LOGGER.info("Group {} is an entity group - {} is of type {}", group, VERTEX_COL_NAME, vertexType);
                structFieldList.add(new StructField(VERTEX_COL_NAME, vertexType, true, Metadata.empty()));
            } else {
                entityOrEdgeByGroup.put(group, EntityOrEdge.EDGE);
                final SchemaEdgeDefinition edgeDefinition = (SchemaEdgeDefinition) elementDefn;
                final String srcClass = store.getSchema().getType(edgeDefinition.getSource()).getClassString();
                final String dstClass = store.getSchema().getType(edgeDefinition.getDestination()).getClassString();
                final DataType srcType = getType(srcClass);
                final DataType dstType = getType(dstClass);
                if (srcType == null || dstType == null) {
                    throw new RuntimeException("Both source and destination must be recognised types: source was "
                            + srcClass + " destination was " + dstClass);
                }
                LOGGER.info("Group {} is an edge group - {} is of type {}, {} is of type {}", group, SRC_COL_NAME, srcType,
                        DST_COL_NAME, dstType);
                structFieldList.add(new StructField(SRC_COL_NAME, srcType, true, Metadata.empty()));
                structFieldList.add(new StructField(DST_COL_NAME, dstType, true, Metadata.empty()));
            }
            final Set<String> properties = elementDefn.getProperties();
            for (final String property : properties) {
                // Check if property is of a known type that can be handled by default
                final String propertyClass = elementDefn.getPropertyClass(property).getCanonicalName();
                DataType propertyType = getType(propertyClass);
                if (propertyType != null) {
                    propertyNeedsConversion.put(property, needsConversion(propertyClass));
                    structFieldList.add(new StructField(property, propertyType, true, Metadata.empty()));
                    LOGGER.info("Property {} is of type {}", property, propertyType);
                } else {
                    // Check if any of the provided converters can handle it
                    if (converters != null) {
                        for (final Converter converter : converters) {
                            if (converter.canHandle(elementDefn.getPropertyClass(property))) {
                                propertyNeedsConversion.put(property, true);
                                propertyType = converter.convertedType();
                                converterByProperty.put(property, converter);
                                structFieldList.add(new StructField(property, propertyType, true, Metadata.empty()));
                                LOGGER.info("Property {} of type {} will be converted by {} to {}",
                                        property,
                                        propertyClass,
                                        converter.getClass().getName(),
                                        propertyType);
                                break;
                            }
                        }
                        if (propertyType == null) {
                            LOGGER.warn("Ignoring property {} as it is not a recognised type and none of the provided "
                                    + "converters can handle it", property);
                        }
                    }
                }
            }
            structTypeByGroup.put(group, new StructType(structFieldList.toArray(new StructField[structFieldList.size()])));
        }
        // Create reverse map of field name to StructField
        final Map<String, Set<StructField>> fieldToStructs = new HashMap<>();
        for (final String group : groups) {
            final StructType groupSchema = structTypeByGroup.get(group);
            for (final String field : groupSchema.fieldNames()) {
                if (fieldToStructs.get(field) == null) {
                    fieldToStructs.put(field, new HashSet<StructField>());
                }
                fieldToStructs.get(field).add(groupSchema.apply(field));
            }
        }
        // Check consistency, i.e. if the same field appears in multiple groups then the types are consistent
        for (final Map.Entry<String, Set<StructField>> entry : fieldToStructs.entrySet()) {
            final Set<StructField> schemas = entry.getValue();
            if (schemas.size() > 1) {
                throw new IllegalArgumentException("Inconsistent fields: the field "
                        + entry.getKey()
                        + " has more than one definition: "
                        + StringUtils.join(schemas, ','));
            }
        }
        // Merge schemas for groups together - fields should appear in the order the groups were provided
        final LinkedHashSet<StructField> fields = new LinkedHashSet<>();
        fields.add(new StructField(GROUP, DataTypes.StringType, false, Metadata.empty()));
        usedProperties.add(GROUP);
        for (final String group : groups) {
            final StructType groupSchema = structTypeByGroup.get(group);
            for (final String field : groupSchema.fieldNames()) {
                final StructField struct = groupSchema.apply(field);
                // Add struct to fields unless it has already been added
                if (!fields.contains(struct)) {
                    fields.add(struct);
                    usedProperties.add(field);
                }
            }
        }
        structType = new StructType(fields.toArray(new StructField[fields.size()]));
        LOGGER.info("Schema is {}", structType);
        LOGGER.debug("properties -> conversion: {}", StringUtils.join(propertyNeedsConversion.entrySet(), ','));
    }

    /**
     * Converts an {@link Entity} to a {@link Row}, only including properties whose name is in the provided
     * {@link LinkedHashSet}.
     *
     * @param entity     The {@link Entity} to convert.
     * @param properties The properties to be included in the conversion.
     * @param propertyNeedsConversion A Map indicating whether a property needs to be converted before it can be inserted
     *                                into the Dataframe.
     * @param convertersByProperty A Map giving the Converter for a property.
     * @return a {@link Row} containing the vertex and the required properties.
     */
    private static Row getRowFromEntity(final Entity entity,
                                        final LinkedHashSet<String> properties,
                                        final Map<String, Boolean> propertyNeedsConversion,
                                        final Map<String, Converter> convertersByProperty) {
        final scala.collection.mutable.MutableList<Object> fields = new scala.collection.mutable.MutableList<>();
        for (final String property : properties) {
            switch (property) {
                case GROUP:
                    fields.appendElem(entity.getGroup());
                    break;
                case VERTEX_COL_NAME:
                    fields.appendElem(entity.getVertex());
                    break;
                default:
                    final Object value = entity.getProperties().get(property);
                    if (value == null) {
                        fields.appendElem(null);
                    } else {
                        if (!propertyNeedsConversion.get(property)) {
                            fields.appendElem(entity.getProperties().get(property));
                        } else {
                            fields.appendElem(convertProperty(property, entity.getProperties().get(property), convertersByProperty));
                        }
                    }
            }
        }
        return Row$.MODULE$.fromSeq(fields);
    }

    /**
     * Converts an {@link Edge} to a {@link Row}, only including properties whose name is in the provided
     * {@link LinkedHashSet}.
     *
     * @param edge       The {@link Edge} to convert.
     * @param properties The properties to be included in the conversion.
     * @param propertyNeedsConversion A Map indicating whether a property needs to be converted before it can be inserted
     *                                into the Dataframe.
     * @param convertersByProperty A Map giving the Converter for a property.
     * @return A {@link Row} containing the source, destination, and the required properties.
     */
    private static Row getRowFromEdge(final Edge edge,
                                      final LinkedHashSet<String> properties,
                                      final Map<String, Boolean> propertyNeedsConversion,
                                      final Map<String, Converter> convertersByProperty) {
        final scala.collection.mutable.MutableList<Object> fields = new scala.collection.mutable.MutableList<>();
        for (final String property : properties) {
            switch (property) {
                case GROUP:
                    fields.appendElem(edge.getGroup());
                    break;
                case SRC_COL_NAME:
                    fields.appendElem(edge.getSource());
                    break;
                case DST_COL_NAME:
                    fields.appendElem(edge.getDestination());
                    break;
                default:
                    final Object value = edge.getProperties().get(property);
                    if (value == null) {
                        fields.appendElem(null);
                    } else {
                        if (!propertyNeedsConversion.get(property)) {
                            fields.appendElem(edge.getProperties().get(property));
                        } else {
                            fields.appendElem(convertProperty(property, edge.getProperties().get(property), convertersByProperty));
                        }
                    }
            }
        }
        return Row$.MODULE$.fromSeq(fields);
    }

    private static DataType getType(final String className) {
        switch (className) {
            case "java.lang.String":
                return DataTypes.StringType;
            case "java.lang.Integer":
                return DataTypes.IntegerType;
            case "java.lang.Long":
                return DataTypes.LongType;
            case "java.lang.Boolean":
                return DataTypes.BooleanType;
            case "java.lang.Double":
                return DataTypes.DoubleType;
            case "java.lang.Float":
                return DataTypes.FloatType;
            case "java.lang.Byte":
                return DataTypes.ByteType;
            case "java.lang.Short":
                return DataTypes.ShortType;
            default:
                return null;
        }
    }

    private static boolean needsConversion(final String className) {
        switch (className) {
            case "java.lang.String":
                return false;
            case "java.lang.Integer":
                return false;
            case "java.lang.Long":
                return false;
            case "java.lang.Boolean":
                return false;
            case "java.lang.Double":
                return false;
            case "java.lang.Float":
                return false;
            case "java.lang.Byte":
                return false;
            case "java.lang.Short":
                return false;
            default:
                return true;
        }
    }

    /**
     * Converts properties to values that can be included in a Dataframe.
     *
     * @param name     The name of the property to be converted.
     * @param property The value of the property to be converted.
     * @param convertersByProperty A Map giving the Converter for a property.
     * @return The property converted to a valid {@link DataType}.
     */
    private static Object convertProperty(final String name,
                                          final Object property,
                                          final Map<String, Converter> convertersByProperty) {
        final Converter converter = convertersByProperty.get(name);
        if (converter != null) {
            try {
                return converter.convert(property);
            } catch (final ConversionException e) {
                return null;
            }
        }
        return null;
    }

    static class ElementToRow extends AbstractFunction1<Element, Row> implements Serializable {

        private static final long serialVersionUID = 3090917576150868059L;
        private final LinkedHashSet<String> properties;
        private final Map<String, Boolean> propertyNeedsConversion;
        private final Map<String, Converter> convertersByProperty;

        ElementToRow(final LinkedHashSet<String> properties,
                     final Map<String, Boolean> propertyNeedsConversion,
                     final Map<String, Converter> convertersByProperty) {
            this.properties = properties;
            this.propertyNeedsConversion = propertyNeedsConversion;
            this.convertersByProperty = convertersByProperty;
        }

        @SuppressFBWarnings(value = "BC_UNCONFIRMED_CAST", justification = "If not an Entity then must be an Edge")
        @Override
        public Row apply(final Element element) {
            if (element instanceof Entity) {
                return getRowFromEntity((Entity) element, properties, propertyNeedsConversion, convertersByProperty);
            }
            return getRowFromEdge((Edge) element, properties, propertyNeedsConversion, convertersByProperty);
        }
    }

}
