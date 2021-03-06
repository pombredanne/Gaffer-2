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

package uk.gov.gchq.gaffer.store.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.gchq.gaffer.data.element.IdentifierType;
import uk.gov.gchq.gaffer.data.element.function.ElementAggregator;
import uk.gov.gchq.gaffer.data.element.function.ElementFilter;
import uk.gov.gchq.koryphe.ValidationResult;
import uk.gov.gchq.koryphe.signature.Signature;
import uk.gov.gchq.koryphe.tuple.binaryoperator.TupleAdaptedBinaryOperator;
import uk.gov.gchq.koryphe.tuple.predicate.TupleAdaptedPredicate;
import java.util.HashSet;
import java.util.Set;

/**
 * An <code>SchemaElementDefinitionValidator</code> validates a {@link SchemaElementDefinition}.
 * Checks all function input and output types are compatible with the
 * properties and identifiers provided.
 * To be able to aggregate 2 similar elements together ALL properties have to
 * be aggregated together. So this validator checks that either no properties have
 * aggregator functions or all properties have aggregator functions defined.
 */
public class SchemaElementDefinitionValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaElementDefinitionValidator.class);

    /**
     * Checks each identifier and property has a type associated with it.
     * Checks all {@link java.util.function.Predicate}s and {@link java.util.function.BinaryOperator}s defined are
     * compatible with the identifiers and properties - this is done by comparing the function input and output types with
     * the identifier and property types.
     *
     * @param elementDef          the {@link uk.gov.gchq.gaffer.data.elementdefinition.ElementDefinition} to validate
     * @param requiresAggregators true if aggregators are required
     * @return true if the element definition is valid, otherwise false and an error is logged
     */
    public ValidationResult validate(final SchemaElementDefinition elementDef, final boolean requiresAggregators) {
        final ValidationResult result = new ValidationResult();

        final ElementFilter validator = elementDef.getValidator();
        final ElementAggregator aggregator = elementDef.getAggregator();
        result.add(validateComponentTypes(elementDef));
        result.add(validateAggregator(aggregator, elementDef, requiresAggregators));
        result.add(validateFunctionArgumentTypes(validator, elementDef));
        result.add(validateFunctionArgumentTypes(aggregator, elementDef));

        return result;
    }

    protected ValidationResult validateComponentTypes(final SchemaElementDefinition elementDef) {
        final ValidationResult result = new ValidationResult();
        for (final IdentifierType idType : elementDef.getIdentifiers()) {
            try {
                if (null == elementDef.getIdentifierClass(idType)) {
                    result.addError("Class for " + idType + " could not be found.");
                }
            } catch (final IllegalArgumentException e) {
                result.addError("Class " + elementDef.getIdentifierTypeName(idType) + " for identifier " + idType + " could not be found");
            }
        }

        for (final String propertyName : elementDef.getProperties()) {
            if (null != IdentifierType.fromName(propertyName)) {
                result.addError("Property name " + propertyName + " is a reserved word. Please use a different property name.");
            } else {
                try {
                    if (null == elementDef.getPropertyClass(propertyName)) {
                        result.addError("Class for " + propertyName + " could not be found.");
                    }
                } catch (final IllegalArgumentException e) {
                    result.addError("Class " + elementDef.getPropertyTypeName(propertyName) + " for property " + propertyName + " could not be found");
                }
            }
        }

        return result;
    }

    protected ValidationResult validateFunctionArgumentTypes(
            final ElementFilter filter, final SchemaElementDefinition schemaElDef) {
        final ValidationResult result = new ValidationResult();
        if (null != filter && null != filter.getComponents()) {
            for (final TupleAdaptedPredicate<String, ?> adaptedPredicate : filter.getComponents()) {
                if (null == adaptedPredicate.getPredicate()) {
                    result.addError(filter.getClass().getSimpleName() + " contains a null function.");
                } else {
                    final Signature inputSig = Signature.getInputSignature(adaptedPredicate.getPredicate());
                    result.add(inputSig.assignable(getTypeClasses(adaptedPredicate.getSelection(), schemaElDef)));
                }
            }
        }

        return result;
    }

    protected ValidationResult validateFunctionArgumentTypes(
            final ElementAggregator aggregator,
            final SchemaElementDefinition schemaElDef) {
        final ValidationResult result = new ValidationResult();
        if (null != aggregator && null != aggregator.getComponents()) {
            for (final TupleAdaptedBinaryOperator<String, ?> adaptedFunction : aggregator.getComponents()) {
                if (null == adaptedFunction.getBinaryOperator()) {
                    result.addError(aggregator.getClass().getSimpleName() + " contains a null function.");
                } else {
                    final Signature inputSig = Signature.getInputSignature(adaptedFunction.getBinaryOperator());
                    result.add(inputSig.assignable(getTypeClasses(adaptedFunction.getSelection(), schemaElDef)));

                    final Signature outputSig = Signature.getOutputSignature(adaptedFunction.getBinaryOperator());
                    result.add(outputSig.assignable(getTypeClasses(adaptedFunction.getSelection(), schemaElDef)));
                }
            }
        }

        return result;
    }

    private ValidationResult validateAggregator(final ElementAggregator aggregator, final SchemaElementDefinition elementDef, final boolean requiresAggregators) {
        final ValidationResult result = new ValidationResult();

        if (null == elementDef.getPropertyMap() || elementDef.getPropertyMap().isEmpty()) {
            // if no properties then no aggregation is necessary
            return result;
        }

        if (null == aggregator || null == aggregator.getComponents() || aggregator.getComponents().isEmpty()) {
            if (requiresAggregators) {
                result.addError("This framework requires that either all of the defined properties have an aggregator function associated with them, or none of them do.");
            }

            // if aggregate functions are not defined then it is valid
            return result;
        }

        // if aggregate functions are defined then check all properties are aggregated
        final Set<String> aggregatedProperties = new HashSet<>();
        if (aggregator.getComponents() != null) {
            for (final TupleAdaptedBinaryOperator<String, ?> adaptedFunction : aggregator.getComponents()) {
                final String[] selection = adaptedFunction.getSelection();
                if (selection != null) {
                    for (final String key : selection) {
                        final IdentifierType idType = IdentifierType.fromName(key);
                        if (null == idType) {
                            aggregatedProperties.add(key);
                        }
                    }
                }
            }
        }

        final Set<String> propertyNamesTmp = new HashSet<>(elementDef.getProperties());
        propertyNamesTmp.removeAll(aggregatedProperties);
        if (propertyNamesTmp.isEmpty()) {
            return result;
        }

        result.addError("No aggregator found for properties '" + propertyNamesTmp.toString() + "' in the supplied schema. "
                + "This framework requires that either all of the defined properties have an aggregator function associated with them, or none of them do.");
        return result;
    }

    private Class[] getTypeClasses(final String[] keys, final SchemaElementDefinition schemaElDef) {
        final Class[] selectionClasses = new Class[keys.length];
        int i = 0;
        for (final String key : keys) {
            selectionClasses[i] = getTypeClass(key, schemaElDef);
            i++;
        }
        return selectionClasses;
    }

    private Class<?> getTypeClass(final String key, final SchemaElementDefinition schemaElDef) {
        final IdentifierType idType = IdentifierType.fromName(key);
        final Class<?> clazz;
        if (null != idType) {
            clazz = schemaElDef.getIdentifierClass(idType);
        } else {
            clazz = schemaElDef.getPropertyClass(key);
        }
        if (null == clazz) {
            if (null != idType) {
                final String typeName = schemaElDef.getIdentifierTypeName(idType);
                if (null != typeName) {
                    LOGGER.error("No class type found for type definition " + typeName
                            + " used by identifier " + idType
                            + ". Please ensure it is defined in the schema.");
                } else {
                    LOGGER.error("No type definition defined for identifier " + idType
                            + ". Please ensure it is defined in the schema.");
                }
            } else {
                final String typeName = schemaElDef.getPropertyTypeName(key);
                if (null != typeName) {
                    LOGGER.error("No class type found for type definition " + typeName
                            + " used by property " + key
                            + ". Please ensure it is defined in the schema.");
                } else {
                    LOGGER.error("No class type found for property " + key
                            + ". Please ensure it is defined in the schema.");
                }
            }

        }
        return clazz;
    }
}
