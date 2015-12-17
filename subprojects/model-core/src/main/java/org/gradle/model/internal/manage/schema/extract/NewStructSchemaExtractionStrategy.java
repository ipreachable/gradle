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

import com.google.common.base.*;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.*;
import org.gradle.api.Action;
import org.gradle.internal.reflect.MethodSignatureEquivalence;
import org.gradle.model.Unmanaged;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.NewModelProperty;
import org.gradle.model.internal.manage.schema.NewStructSchema;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.Method;
import java.util.*;

public class NewStructSchemaExtractionStrategy implements ModelSchemaExtractionStrategy {
    private static final Equivalence<Method> METHOD_EQUIVALENCE = new MethodSignatureEquivalence();

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

        ImmutableSet.Builder<NewModelProperty<?>> propertiesBuilder = ImmutableSet.builder();
        for (NewModelPropertyExtractionResult<?> propertyResult : validPropertyResults) {
            propertiesBuilder.add(propertyResult.getProperty());
            attachPropertyExtractionContext(extractionContext, propertyResult.getProperty());
        }
        ImmutableSet<NewModelProperty<?>> properties = propertiesBuilder.build();
        Iterable<WeaklyTypeReferencingMethod<?, ?>> nonPropertyMethods = Iterables.transform(nonPropertyMethodsBuilder.build(), new Function<Method, WeaklyTypeReferencingMethod<?, ?>>() {
            @Override
            public WeaklyTypeReferencingMethod<?, ?> apply(Method method) {
                return WeaklyTypeReferencingMethod.of(ModelType.declaringType(method), ModelType.returnType(method), method);
            }
        });

        extractionContext.found(new NewStructSchema<T>(type, properties, nonPropertyMethods, aspects, false));
    }

    private Collection<ModelPropertyExtractionContext> identifyPotentialProperties(Multimap<Wrapper<Method>, Method> allMethods, ImmutableCollection.Builder<Method> nonPropertyMethods) {
        Map<String, ModelPropertyExtractionContext> propertiesMap = Maps.newTreeMap();
        for (Map.Entry<Equivalence.Wrapper<Method>, Collection<Method>> entry : allMethods.asMap().entrySet()) {
            Method method = entry.getKey().get();
            MethodType methodType = MethodType.of(method);
            Collection<Method> methodsWithEqualSignature = entry.getValue();
            switch (methodType) {
                case NON_PROPERTY:
                    nonPropertyMethods.add(method);
                    continue;
                case GET_GETTER:
                    getPropertyContext(propertiesMap, methodType.propertyNameFor(method)).setGetGetter(new PropertyAccessorExtractionContext(methodsWithEqualSignature));
                    break;
                case IS_GETTER:
                    getPropertyContext(propertiesMap, methodType.propertyNameFor(method)).setIsGetter(new PropertyAccessorExtractionContext(methodsWithEqualSignature));
                    break;
                case SETTER:
                    getPropertyContext(propertiesMap, methodType.propertyNameFor(method)).setSetter(new PropertyAccessorExtractionContext(methodsWithEqualSignature));
                    break;
                default:
                    throw new AssertionError();
            }
        }
        return propertiesMap.values();
    }

    private ModelPropertyExtractionContext getPropertyContext(Map<String, ModelPropertyExtractionContext> propertiesMap, String propertyName) {
        ModelPropertyExtractionContext propertyContext = propertiesMap.get(propertyName);
        if (propertyContext == null) {
            propertyContext = new ModelPropertyExtractionContext(propertyName);
            propertiesMap.put(propertyName, propertyContext);
        }
        return propertyContext;
    }

    private void filterInvalidProperties(Iterable<ModelPropertyExtractionContext> propertyContexts, ImmutableCollection.Builder<NewModelPropertyExtractionResult<?>> validProperties, ImmutableCollection.Builder<Method> nonPropertyMethods) {
        for (ModelPropertyExtractionContext propertyContext : propertyContexts) {
            PropertyAccessorExtractionContext getGetter = propertyContext.getGetGetter();
            PropertyAccessorExtractionContext isGetter = propertyContext.getIsGetter();
            PropertyAccessorExtractionContext setter = propertyContext.getSetter();

            // TODO:LPTR Validate setter

            PropertyAccessorExtractionContext chosenGetter;
            boolean declaredAsUnmanaged;
            ImmutableSet.Builder<ModelType<?>> declaredByBuilder = ImmutableSet.builder();

            if (isGetter != null) {
                Method mostSpecificIsGetter = isGetter.getMostSpecificDeclaration();
                Class<?> isReturnType = mostSpecificIsGetter.getReturnType();
                if (isReturnType != Boolean.TYPE) {
                    chosenGetter = getGetter;
                    nonPropertyMethods.add(mostSpecificIsGetter);
                    declaredAsUnmanaged = isDeclaredAsUnmanaged(getGetter);
                    addDeclaredBy(getGetter, declaredByBuilder);
                } else if (getGetter != null) {
                    Method mostSpecificGetGetter = getGetter.getMostSpecificDeclaration();
                    Class<?> getReturnType = mostSpecificGetGetter.getReturnType();
                    if (getReturnType != Boolean.TYPE) {
                        if (setter != null && setter.getMostSpecificDeclaration().getParameterTypes()[0] == Boolean.TYPE) {
                            chosenGetter = isGetter;
                            nonPropertyMethods.add(mostSpecificGetGetter);
                            declaredAsUnmanaged = isDeclaredAsUnmanaged(isGetter);
                            addDeclaredBy(isGetter, declaredByBuilder);
                        } else {
                            chosenGetter = getGetter;
                            nonPropertyMethods.add(mostSpecificIsGetter);
                            declaredAsUnmanaged = isDeclaredAsUnmanaged(getGetter);
                            addDeclaredBy(getGetter, declaredByBuilder);
                        }
                    } else {
                        chosenGetter = isGetter;
                        nonPropertyMethods.add(mostSpecificGetGetter);
                        declaredAsUnmanaged = isDeclaredAsUnmanaged(isGetter) || isDeclaredAsUnmanaged(getGetter);
                        addDeclaredBy(getGetter, declaredByBuilder);
                        addDeclaredBy(isGetter, declaredByBuilder);
                    }
                } else {
                    chosenGetter = isGetter;
                    declaredAsUnmanaged = isDeclaredAsUnmanaged(isGetter);
                    addDeclaredBy(isGetter, declaredByBuilder);
                }
            } else {
                chosenGetter = getGetter;
                declaredAsUnmanaged = isDeclaredAsUnmanaged(getGetter);
                addDeclaredBy(getGetter, declaredByBuilder);
            }

            ModelType<?> propertyType;
            if (chosenGetter != null) {
                Method mostSpecificGetter = chosenGetter.getMostSpecificDeclaration();
                ModelType<?> getterType = ModelType.returnType(mostSpecificGetter);
                if (setter != null) {
                    Method mostSpecificSetter = setter.getMostSpecificDeclaration();
                    ModelType<?> setterType = ModelType.paramType(mostSpecificSetter, 0);
                    if (!getterType.equals(setterType)) {
                        nonPropertyMethods.add(mostSpecificSetter);
                        setter = null;
                    }
                }
                propertyType = getterType;
            } else {
                // If we don't have a getter, we must have a setter, otherwise there's nothing to declare this property
                assert setter != null;
                propertyType = ModelType.paramType(setter.getMostSpecificDeclaration(), 0);
            }

            addDeclaredBy(setter, declaredByBuilder);

            Iterable<PropertyAccessorExtractionContext> allGetters = Iterables.filter(Arrays.asList(isGetter, getGetter), Predicates.<PropertyAccessorExtractionContext>notNull());
            NewModelPropertyExtractionResult<?> propertyResult = createProperty(propertyType, propertyContext.getPropertyName(), allGetters, chosenGetter, setter, declaredByBuilder.build(), declaredAsUnmanaged);
            validProperties.add(propertyResult);
        }
    }

    private static <P> NewModelPropertyExtractionResult<P> createProperty(ModelType<P> propertyType, String propertyName, Iterable<PropertyAccessorExtractionContext> allGetters, PropertyAccessorExtractionContext getter, PropertyAccessorExtractionContext setter, Set<ModelType<?>> declaredBy, boolean declaredAsUnmanaged) {
        WeaklyTypeReferencingMethod<?, P> getterRef;
        if (getter == null) {
            getterRef = null;
        } else {
            Method getterMethod = getter.getMostSpecificDeclaration();
            getterRef = WeaklyTypeReferencingMethod.of(ModelType.declaringType(getterMethod), propertyType, getterMethod);
        }
        WeaklyTypeReferencingMethod<?, Void> setterRef;
        if (setter == null) {
            setterRef = null;
        } else {
            Method setterMethod = setter.getMostSpecificDeclaration();
            setterRef = WeaklyTypeReferencingMethod.of(ModelType.declaringType(setterMethod), ModelType.VOID, setterMethod);
        }
        NewModelProperty<P> property = new NewModelProperty<P>(propertyType, propertyName, declaredBy, getterRef, setterRef, declaredAsUnmanaged);
        return new NewModelPropertyExtractionResult<P>(property, allGetters, setter);
    }

    private static void addDeclaredBy(PropertyAccessorExtractionContext accessor, ImmutableCollection.Builder<ModelType<?>> declaredBy) {
        if (accessor != null) {
            for (Method declaration : accessor.getDeclaringMethods()) {
                declaredBy.add(ModelType.of(declaration.getDeclaringClass()));
            }
        }
    }

    private static boolean isDeclaredAsUnmanaged(PropertyAccessorExtractionContext getter) {
        return getter != null && getter.getMostSpecificDeclaration().isAnnotationPresent(Unmanaged.class);
    }

    private static <T> Multimap<Wrapper<Method>, Method> getAllMethods(ModelType<T> type) {
        Class<T> clazz = type.getConcreteClass();
        final ImmutableListMultimap.Builder<Wrapper<Method>, Method> builder = ImmutableListMultimap.builder();
        ModelSchemaUtils.walkTypeHierarchy(clazz, new ModelSchemaUtils.TypeVisitor<T>() {
            @Override
            public void visitType(Class<? super T> type) {
                Method[] declaredMethods = type.getDeclaredMethods();
                // Sort of determinism
                Arrays.sort(declaredMethods, Ordering.usingToString());
                for (Method method : declaredMethods) {
                    // TODO:LPTR Allow toString() to pass here
                    if (ModelSchemaUtils.isIgnoredMethod(method)) {
                        continue;
                    }
                    builder.put(METHOD_EQUIVALENCE.wrap(method), method);
                }
            }
        });
        return builder.build();
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
