/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.model.internal.manage.schema.extract;

import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.*;
import org.gradle.api.Action;
import org.gradle.model.Unmanaged;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.NewModelProperty;
import org.gradle.model.internal.manage.schema.NewStructSchema;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.gradle.model.internal.manage.schema.extract.ModelSchemaUtils.getAllMethods;
import static org.gradle.model.internal.manage.schema.extract.PropertyAccessorRole.*;

public class NewStructSchemaExtractionStrategy implements ModelSchemaExtractionStrategy {
    private final ModelSchemaAspectExtractor aspectExtractor;

    public NewStructSchemaExtractionStrategy(ModelSchemaAspectExtractor aspectExtractor) {
        this.aspectExtractor = aspectExtractor;
    }

    @Override
    public <T> void extract(ModelSchemaExtractionContext<T> extractionContext) {
        final ModelType<T> type = extractionContext.getType();
        Multimap<Wrapper<Method>, Method> allMethods = getAllMethods(type);

        ImmutableSet.Builder<Method> nonPropertyMethodsBuilder = ImmutableSet.builder();
        Collection<ModelPropertyExtractionContext> potentialProperties = identifyPotentialProperties(allMethods, nonPropertyMethodsBuilder);

        ImmutableSet.Builder<NewModelPropertyExtractionResult<?>> validPropertiesBuilder = ImmutableSet.builder();
        filterInvalidProperties(potentialProperties, validPropertiesBuilder, nonPropertyMethodsBuilder);

        Set<NewModelPropertyExtractionResult<?>> validPropertyResults = validPropertiesBuilder.build();
        Iterable<? extends ModelSchemaAspect> aspects = aspectExtractor.extract(extractionContext, validPropertyResults);

        ImmutableSortedMap.Builder<String, NewModelProperty<?>> propertiesBuilder = ImmutableSortedMap.naturalOrder();
        for (NewModelPropertyExtractionResult<?> propertyResult : validPropertyResults) {
            propertiesBuilder.put(propertyResult.getProperty().getName(), propertyResult.getProperty());
            attachPropertyExtractionContext(extractionContext, propertyResult.getProperty());
        }
        Map<String, NewModelProperty<?>> properties = propertiesBuilder.build();
        Iterable<WeaklyTypeReferencingMethod<?, ?>> nonPropertyMethods = Iterables.transform(nonPropertyMethodsBuilder.build(), new Function<Method, WeaklyTypeReferencingMethod<?, ?>>() {
            @Override
            public WeaklyTypeReferencingMethod<?, ?> apply(Method method) {
                return WeaklyTypeReferencingMethod.of(method);
            }
        });

        extractionContext.found(new NewStructSchema<T>(type, properties, nonPropertyMethods, aspects));
    }

    private Collection<ModelPropertyExtractionContext> identifyPotentialProperties(Multimap<Wrapper<Method>, Method> allMethods, ImmutableCollection.Builder<Method> nonPropertyMethods) {
        Map<String, ModelPropertyExtractionContext> propertiesMap = Maps.newTreeMap();
        for (Map.Entry<Equivalence.Wrapper<Method>, Collection<Method>> entry : allMethods.asMap().entrySet()) {
            Method method = entry.getKey().get();
            PropertyAccessorRole role = PropertyAccessorRole.of(method);
            if (role == null) {
                nonPropertyMethods.add(method);
                continue;
            }
            String propertyName = role.propertyNameFor(method);
            ModelPropertyExtractionContext propertyContext = propertiesMap.get(propertyName);
            if (propertyContext == null) {
                propertyContext = new ModelPropertyExtractionContext(propertyName);
                propertiesMap.put(propertyName, propertyContext);
            }
            Collection<Method> methodsWithEqualSignature = entry.getValue();
            propertyContext.addAccessor(new PropertyAccessorExtractionContext(role, methodsWithEqualSignature));
        }
        return propertiesMap.values();
    }

    private void filterInvalidProperties(Iterable<ModelPropertyExtractionContext> propertyContexts, ImmutableCollection.Builder<NewModelPropertyExtractionResult<?>> validProperties, ImmutableCollection.Builder<Method> nonPropertyMethods) {
        for (ModelPropertyExtractionContext propertyContext : propertyContexts) {
            PropertyAccessorExtractionContext getGetter = propertyContext.getAccessor(GET_GETTER);
            PropertyAccessorExtractionContext isGetter = propertyContext.getAccessor(IS_GETTER);
            PropertyAccessorExtractionContext setter = propertyContext.getAccessor(SETTER);

            // TODO:LPTR Validate setter

            ModelType<?> propertyType;
            if (isGetter != null) {
                if (!isGetter.getType().equals(ModelType.BOOLEAN)) {
                    propertyContext.dropInvalidAccessor(IS_GETTER, nonPropertyMethods);
                }
            }

            if (isGetter != null) {
                if (getGetter != null) {
                    if (!getGetter.getType().equals(ModelType.BOOLEAN)) {
                        if (setter != null) {
                            if (setter.getType().equals(getGetter.getType())) {
                                propertyType = setter.getType();
                                propertyContext.dropInvalidAccessor(IS_GETTER, nonPropertyMethods);
                            } else if (setter.getType().equals(ModelType.BOOLEAN)) {
                                propertyType = ModelType.BOOLEAN;
                                propertyContext.dropInvalidAccessor(GET_GETTER, nonPropertyMethods);
                            } else {
                                propertyType = ModelType.BOOLEAN;
                                propertyContext.dropInvalidAccessor(GET_GETTER, nonPropertyMethods);
                                propertyContext.dropInvalidAccessor(SETTER, nonPropertyMethods);
                            }
                        } else {
                            propertyType = ModelType.BOOLEAN;
                            propertyContext.dropInvalidAccessor(GET_GETTER, nonPropertyMethods);
                        }
                    } else {
                        propertyType = ModelType.BOOLEAN;
                        if (setter != null && !setter.getType().equals(ModelType.BOOLEAN)) {
                            propertyContext.dropInvalidAccessor(SETTER, nonPropertyMethods);
                        }
                    }
                } else {
                    propertyType = ModelType.BOOLEAN;
                    if (setter != null && !setter.getType().equals(ModelType.BOOLEAN)) {
                        propertyContext.dropInvalidAccessor(SETTER, nonPropertyMethods);
                    }
                }
            } else if (getGetter != null) {
                propertyType = getGetter.getType();
                if (setter != null && !setter.getType().equals(propertyType)) {
                    propertyContext.dropInvalidAccessor(SETTER, nonPropertyMethods);
                }
            } else {
                assert setter != null;
                propertyType = setter.getType();
            }

            NewModelPropertyExtractionResult<?> propertyResult = createProperty(propertyType, propertyContext);
            validProperties.add(propertyResult);
        }
    }

    private static <P> NewModelPropertyExtractionResult<P> createProperty(ModelType<P> propertyType, ModelPropertyExtractionContext propertyContext) {
        ImmutableMap.Builder<PropertyAccessorRole, WeaklyTypeReferencingMethod<?, ?>> accessors = ImmutableMap.builder();
        for (PropertyAccessorExtractionContext accessor : propertyContext.getAccessors()) {
            Method method = accessor.getMostSpecificDeclaration();
            WeaklyTypeReferencingMethod<?, ?> methodRef = WeaklyTypeReferencingMethod.of(method);
            accessors.put(accessor.getRole(), methodRef);
        }
        NewModelProperty<P> property = new NewModelProperty<P>(
            propertyType,
            propertyContext.getPropertyName(),
            propertyContext.getDeclaredBy(),
            accessors.build(),
            propertyContext.isDeclaredAsUnmanaged()
        );
        return new NewModelPropertyExtractionResult<P>(property, propertyContext.getAccessors());
    }

    private static boolean isDeclaredAsUnmanaged(PropertyAccessorExtractionContext getter) {
        return getter != null && getter.getMostSpecificDeclaration().isAnnotationPresent(Unmanaged.class);
    }

    private <T, P> void attachPropertyExtractionContext(ModelSchemaExtractionContext<T> extractionContext, final NewModelProperty<P> property) {
        String propertyDescription = propertyDescription(extractionContext, property);
        extractionContext.child(property.getType(), propertyDescription, new Action<ModelSchema<P>>() {
            @Override
            public void execute(ModelSchema<P> propertySchema) {
                property.setSchema(propertySchema);
            }
        });
    }

    private static String propertyDescription(ModelSchemaExtractionContext<?> parentContext, NewModelProperty<?> property) {
        if (property.getDeclaredBy().size() == 1 && property.getDeclaredBy().contains(parentContext.getType())) {
            return String.format("property '%s'", property.getName());
        } else {
            ImmutableSortedSet<String> declaredBy = ImmutableSortedSet.copyOf(Iterables.transform(property.getDeclaredBy(), Functions.toStringFunction()));
            return String.format("property '%s' declared by %s", property.getName(), Joiner.on(", ").join(declaredBy));
        }
    }

}
