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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import groovy.lang.Closure;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import org.gradle.api.internal.ClosureBackedAction;
import org.gradle.internal.Cast;
import org.gradle.internal.typeconversion.TypeConverter;
import org.gradle.model.internal.asm.AsmClassGeneratorUtils;
import org.gradle.model.internal.core.MutableModelNode;
import org.gradle.model.internal.manage.instance.GeneratedViewState;
import org.gradle.model.internal.manage.instance.ManagedInstance;
import org.gradle.model.internal.manage.instance.ManagedStructBindingStore;
import org.gradle.model.internal.manage.instance.ModelElementState;
import org.gradle.model.internal.manage.schema.CompositeSchema;
import org.gradle.model.internal.manage.schema.NewModelProperty;
import org.gradle.model.internal.manage.schema.NewStructSchema;
import org.gradle.model.internal.manage.schema.ScalarValueSchema;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;
import org.objectweb.asm.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.gradle.model.internal.manage.schema.extract.PropertyAccessorRole.*;
import static org.objectweb.asm.Opcodes.*;

public class NewManagedProxyClassGenerator extends AbstractProxyClassGenerator {
    /*
        Note: there is deliberately no internal synchronizing or caching at this level.
        Class generation should be performed behind a ManagedProxyFactory.
     */

    private static final String STATE_FIELD_NAME = "$state";
    private static final String TYPE_CONVERTER_FIELD_NAME = "$typeConverter";
    private static final String MANAGED_TYPE_FIELD_NAME = "$managedType";
    private static final String DELEGATE_FIELD_NAME = "$delegate";
    private static final String CAN_CALL_SETTERS_FIELD_NAME = "$canCallSetters";
    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Type STRING_TYPE = Type.getType(String.class);
    private static final Type CLASS_TYPE = Type.getType(Class.class);
    private static final Type CLOSURE_TYPE = Type.getType(Closure.class);
    private static final Type TYPE_CONVERTER_TYPE = Type.getType(TypeConverter.class);
    private static final Type MODEL_TYPE_TYPE = Type.getType(ModelType.class);
    private static final Type GENERATED_VIEW_STATE_TYPE = Type.getType(GeneratedViewState.class);
    private static final Type MODEL_ELEMENT_STATE_TYPE = Type.getType(ModelElementState.class);
    private static final Type GENERATED_VIEW_TYPE = Type.getType(GeneratedView.class);
    private static final String GET_VIEW_STATE_METHOD_DESCRIPTOR = Type.getMethodDescriptor(GENERATED_VIEW_STATE_TYPE);
    private static final String STATE_SET_METHOD_DESCRIPTOR = Type.getMethodDescriptor(Type.VOID_TYPE, STRING_TYPE, OBJECT_TYPE);
    private static final String STATE_GET_METHOD_DESCRIPTOR = Type.getMethodDescriptor(OBJECT_TYPE, STRING_TYPE);
    private static final String STATE_APPLY_METHOD_DESCRIPTOR = Type.getMethodDescriptor(Type.VOID_TYPE, STRING_TYPE, CLOSURE_TYPE);
    private static final String MANAGED_INSTANCE_TYPE = Type.getInternalName(ManagedInstance.class);
    private static final String TO_STRING_METHOD_DESCRIPTOR = Type.getMethodDescriptor(STRING_TYPE);
    private static final String GET_BACKING_NODE_METHOD_DESCRIPTOR = Type.getMethodDescriptor(Type.getType(MutableModelNode.class));
    private static final String MODEL_TYPE_INTERNAL_NAME = MODEL_TYPE_TYPE.getInternalName();
    private static final String MODEL_TYPE_OF_METHOD_DESCRIPTOR = Type.getMethodDescriptor(MODEL_TYPE_TYPE, CLASS_TYPE);
    private static final String GET_MANAGED_TYPE_METHOD_DESCRIPTOR = Type.getMethodDescriptor(MODEL_TYPE_TYPE);
    private static final String GET_PROPERTY_MISSING_METHOD_DESCRIPTOR = Type.getMethodDescriptor(OBJECT_TYPE, STRING_TYPE);
    private static final String MISSING_PROPERTY_EXCEPTION_TYPE = Type.getInternalName(MissingPropertyException.class);
    private static final String CLASS_INTERNAL_NAME = Type.getInternalName(Class.class);
    private static final String FOR_NAME_METHOD_DESCRIPTOR = Type.getMethodDescriptor(CLASS_TYPE, STRING_TYPE);
    private static final String HASH_CODE_METHOD_DESCRIPTOR = Type.getMethodDescriptor(Type.getType(int.class));
    private static final String EQUALS_METHOD_DESCRIPTOR = Type.getMethodDescriptor(Type.getType(boolean.class), OBJECT_TYPE);
    private static final String OBJECT_ARRAY_TYPE = Type.getInternalName(Object[].class);
    private static final String MISSING_METHOD_EXCEPTION_TYPE = Type.getInternalName(MissingMethodException.class);
    private static final String MISSING_PROPERTY_CONSTRUCTOR_DESCRIPTOR = Type.getMethodDescriptor(Type.VOID_TYPE, STRING_TYPE, CLASS_TYPE);
    private static final String METHOD_MISSING_METHOD_DESCRIPTOR = Type.getMethodDescriptor(OBJECT_TYPE, STRING_TYPE, OBJECT_TYPE);
    private static final String SET_PROPERTY_MISSING_METHOD_DESCRIPTOR = Type.getMethodDescriptor(OBJECT_TYPE, STRING_TYPE, OBJECT_TYPE);
    private static final String MISSING_METHOD_EXCEPTION_CONSTRUCTOR_DESCRIPTOR = Type.getMethodDescriptor(Type.VOID_TYPE, STRING_TYPE, CLASS_TYPE, Type.getType(Object[].class));
    private static final String SET_OBJECT_PROPERTY_DESCRIPTOR = Type.getMethodDescriptor(Type.VOID_TYPE, OBJECT_TYPE);
    private static final String COERCE_TO_SCALAR_DESCRIPTOR = Type.getMethodDescriptor(OBJECT_TYPE, OBJECT_TYPE, CLASS_TYPE, Type.getType(boolean.class));
    private static final String MODEL_ELEMENT_STATE_TYPE_INTERNAL_NAME = MODEL_ELEMENT_STATE_TYPE.getInternalName();
    private static final Map<Class<?>, Class<?>> BOXED_TYPES = ImmutableMap.<Class<?>, Class<?>>builder()
        .put(byte.class, Byte.class)
        .put(short.class, Short.class)
        .put(int.class, Integer.class)
        .put(boolean.class, Boolean.class)
        .put(float.class, Float.class)
        .put(char.class, Character.class)
        .put(double.class, Double.class)
        .put(long.class, Long.class)
        .build();

    /**
     * Generates an implementation of the given managed type.
     * <p>
     * The generated class will implement/extend the managed type and will:
     * <ul>
     *     <li>provide implementations for abstract getters and setters that delegate to the backing state</li>
     *     <li>provide a `toString()` implementation</li>
     *     <li>mix-in implementation of {@link ManagedInstance}</li>
     *     <li>provide a constructor that accepts a {@link ModelElementState}, which will be used to implement the above.</li>
     * </ul>
     *
     * In case a delegate schema is supplied, the generated class will also have:
     * <ul>
     *     <li>a constructor that also takes a delegate instance</li>
     *     <li>methods that call through to the delegate instance</li>
     * </ul>
     */
    public <T, M extends T, D extends T> Class<? extends M> generate(Class<? extends GeneratedViewState> backingStateType, NewStructSchema<M> viewSchema, ManagedStructBindingStore.ManagedStructBinding bindings) {
        NewStructSchema<D> delegateSchema = Cast.uncheckedCast(bindings.getDelegateSchema());
        if (!bindings.getViewSchemas().contains(viewSchema)) {
            throw new IllegalArgumentException(String.format("View schema '%s' is not supported by bindings", viewSchema.getType()));
        }
        ClassWriter visitor = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        ModelType<M> viewType = viewSchema.getType();

        StringBuilder generatedTypeNameBuilder = new StringBuilder(viewType.getName());
        if (backingStateType == GeneratedViewState.class) {
            generatedTypeNameBuilder.append("$View");
        } else {
            generatedTypeNameBuilder.append("$NodeView");
        }
        if (delegateSchema != null) {
            generatedTypeNameBuilder.append("$").append(delegateSchema.getType().getName().replaceAll("\\.", "_"));
        }

        String generatedTypeName = generatedTypeNameBuilder.toString();
        Type generatedType = Type.getType("L" + generatedTypeName.replaceAll("\\.", "/") + ";");

        Class<M> viewClass = viewType.getConcreteClass();
        Class<?> superclass;
        final ImmutableSet.Builder<String> interfacesToImplement = ImmutableSet.builder();
        interfacesToImplement.add(GENERATED_VIEW_TYPE.getInternalName());
        if (backingStateType == ModelElementState.class) {
            interfacesToImplement.add(MANAGED_INSTANCE_TYPE);
        }
        if (viewClass.isInterface()) {
            superclass = Object.class;
            interfacesToImplement.add(Type.getInternalName(viewClass));
        } else {
            superclass = viewClass;
        }
        // TODO:LPTR This should be removed once BinaryContainer is a ModelMap
        // We need to also implement all the interfaces of the delegate type because otherwise
        // BinaryContainer won't recognize managed binaries as BinarySpecInternal
        if (delegateSchema != null) {
            ModelSchemaUtils.walkTypeHierarchy(delegateSchema.getType().getConcreteClass(), new ModelSchemaUtils.TypeVisitor<D>() {
                @Override
                public void visitType(Class<? super D> type) {
                    if (type.isInterface()) {
                        interfacesToImplement.add(Type.getInternalName(type));
                    }
                }
            });
        }

        generateProxyClass(visitor, viewSchema, bindings, interfacesToImplement.build(), generatedType, Type.getType(superclass), backingStateType);

        ClassLoader targetClassLoader = viewClass.getClassLoader();
        if (delegateSchema != null) {
            // TODO - remove this once the above is removed
            try {
                viewClass.getClassLoader().loadClass(delegateSchema.getType().getConcreteClass().getName());
            } catch (ClassNotFoundException e) {
                // Delegate class is not visible to managed view type -> view type is more general than delegate type, so use the delegate classloader instead
                targetClassLoader = delegateSchema.getType().getConcreteClass().getClassLoader();
            }
        }

        return defineClass(visitor, targetClassLoader, generatedTypeName);
    }

    private void generateProxyClass(ClassWriter visitor, NewStructSchema<?> viewSchema, ManagedStructBindingStore.ManagedStructBinding bindings, Collection<String> interfacesToImplement,
                                    Type generatedType, Type superclassType, Class<? extends GeneratedViewState> backingStateType) {
        Class<?> viewClass = viewSchema.getType().getConcreteClass();
        declareClass(visitor, interfacesToImplement, generatedType, superclassType);
        declareStateField(visitor);
        declareTypeConverterField(visitor);
        declareManagedTypeField(visitor);
        declareCanCallSettersField(visitor);
        writeStaticConstructor(visitor, generatedType, viewClass);
        writeConstructor(visitor, generatedType, superclassType, bindings.getDelegateSchema(), Type.getType(backingStateType));
        writeToString(visitor, generatedType, viewClass, bindings.getDelegateSchema());
        writeGeneratedViewMethods(visitor, generatedType);
        if (backingStateType == ModelElementState.class) {
            writeManagedInstanceMethods(visitor, generatedType);
        }
        writeGroovyMethods(visitor, viewClass);
        writeViewMethods(visitor, generatedType, viewSchema, bindings);
        writeHashCodeMethod(visitor, generatedType);
        writeEqualsMethod(visitor, generatedType);
        visitor.visitEnd();
    }

    private void declareClass(ClassVisitor visitor, Collection<String> interfaceInternalNames, Type generatedType, Type superclassType) {
        visitor.visit(V1_6, ACC_PUBLIC, generatedType.getInternalName(), null,
            superclassType.getInternalName(), Iterables.toArray(interfaceInternalNames, String.class));
    }

    private void declareStateField(ClassVisitor visitor) {
        declareField(visitor, STATE_FIELD_NAME, GeneratedViewState.class);
    }

    private void declareTypeConverterField(ClassVisitor visitor) {
        declareField(visitor, TYPE_CONVERTER_FIELD_NAME, TypeConverter.class);
    }

    private void declareManagedTypeField(ClassVisitor visitor) {
        declareStaticField(visitor, MANAGED_TYPE_FIELD_NAME, ModelType.class);
    }

    private void declareDelegateField(ClassVisitor visitor, NewStructSchema<?> delegateSchema) {
        declareField(visitor, DELEGATE_FIELD_NAME, delegateSchema.getType().getConcreteClass());
    }

    private void declareCanCallSettersField(ClassVisitor visitor) {
        declareField(visitor, CAN_CALL_SETTERS_FIELD_NAME, Boolean.TYPE);
    }

    private void declareField(ClassVisitor visitor, String name, Class<?> fieldClass) {
        visitor.visitField(ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC, name, Type.getDescriptor(fieldClass), null, null);
    }

    private FieldVisitor declareStaticField(ClassVisitor visitor, String name, Class<?> fieldClass) {
        return visitor.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC | ACC_SYNTHETIC, name, Type.getDescriptor(fieldClass), null, null);
    }

    private void writeConstructor(ClassVisitor visitor, Type generatedType, Type superclassType, NewStructSchema<?> delegateSchema, Type backingStateType) {
        String constructorDescriptor;
        Type delegateType;
        if (delegateSchema == null) {
            delegateType = null;
            constructorDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, backingStateType, TYPE_CONVERTER_TYPE);
        } else {
            delegateType = Type.getType(delegateSchema.getType().getConcreteClass());
            constructorDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, backingStateType, TYPE_CONVERTER_TYPE, delegateType);
        }
        MethodVisitor constructorVisitor = declareMethod(visitor, CONSTRUCTOR_NAME, constructorDescriptor, CONCRETE_SIGNATURE);

        invokeSuperConstructor(constructorVisitor, superclassType);
        assignStateField(constructorVisitor, generatedType);
        assignTypeConverterField(constructorVisitor, generatedType);
        if (delegateType != null) {
            assignDelegateField(constructorVisitor, generatedType, delegateType);
        }
        setCanCallSettersField(constructorVisitor, generatedType, true);
        finishVisitingMethod(constructorVisitor);
    }

    private void writeStaticConstructor(ClassVisitor visitor, Type generatedType, Class<?> managedTypeClass) {
        MethodVisitor constructorVisitor = declareMethod(visitor, STATIC_CONSTRUCTOR_NAME, "()V", CONCRETE_SIGNATURE, ACC_STATIC);
        writeManagedTypeStaticField(generatedType, managedTypeClass, constructorVisitor);
        finishVisitingMethod(constructorVisitor);
    }

    private void writeManagedTypeStaticField(Type generatedType, Class<?> managedTypeClass, MethodVisitor constructorVisitor) {
        constructorVisitor.visitLdcInsn(Type.getType(managedTypeClass));
        constructorVisitor.visitMethodInsn(INVOKESTATIC, MODEL_TYPE_INTERNAL_NAME, "of", MODEL_TYPE_OF_METHOD_DESCRIPTOR, false);
        constructorVisitor.visitFieldInsn(PUTSTATIC, generatedType.getInternalName(), MANAGED_TYPE_FIELD_NAME, Type.getDescriptor(ModelType.class));
    }

    private void invokeSuperConstructor(MethodVisitor constructorVisitor, Type superclassType) {
        putThisOnStack(constructorVisitor);
        constructorVisitor.visitMethodInsn(INVOKESPECIAL, superclassType.getInternalName(), CONSTRUCTOR_NAME, Type.getMethodDescriptor(Type.VOID_TYPE), false);
    }

    // TODO:LPTR This decision should happen in ManagedStructBinding
    private void writeToString(ClassVisitor visitor, Type generatedType, Class<?> viewClass, NewStructSchema<?> delegateSchema) {
        Method toStringMethod = getToStringMethod(viewClass);

        if (toStringMethod != null && !toStringMethod.getDeclaringClass().equals(Object.class)) {
            writeNonAbstractMethodWrapper(visitor, generatedType, viewClass, toStringMethod);
        } else if (delegateSchema != null && delegateSchema.hasProperty("displayName")) {
            writeDelegatingToString(visitor, generatedType, Type.getType(delegateSchema.getType().getConcreteClass()));
        } else {
            writeDefaultToString(visitor, generatedType);
        }
    }

    private void writeDelegatingToString(ClassVisitor visitor, Type generatedType, Type delegateType) {
        MethodVisitor methodVisitor = declareMethod(visitor, "toString", TO_STRING_METHOD_DESCRIPTOR, CONCRETE_SIGNATURE);
        putDelegateFieldValueOnStack(methodVisitor, generatedType, delegateType);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, delegateType.getInternalName(), "getDisplayName", TO_STRING_METHOD_DESCRIPTOR, false);
        finishVisitingMethod(methodVisitor, ARETURN);
    }

    private void writeDefaultToString(ClassVisitor visitor, Type generatedType) {
        MethodVisitor methodVisitor = declareMethod(visitor, "toString", TO_STRING_METHOD_DESCRIPTOR, CONCRETE_SIGNATURE);
        putStateFieldValueOnStack(methodVisitor, generatedType);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, GENERATED_VIEW_STATE_TYPE.getInternalName(), "getDisplayName", TO_STRING_METHOD_DESCRIPTOR, true);
        finishVisitingMethod(methodVisitor, ARETURN);
    }

    private Method getToStringMethod(Class<?> managedTypeClass) {
        try {
            return managedTypeClass.getMethod("toString");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private void writeGroovyMethods(ClassVisitor visitor, Class<?> managedTypeClass) {
        // Object propertyMissing(String name)
        MethodVisitor methodVisitor = declareMethod(visitor, "propertyMissing", GET_PROPERTY_MISSING_METHOD_DESCRIPTOR, CONCRETE_SIGNATURE);

        // throw new MissingPropertyException(name, <managed-type>.class)
        methodVisitor.visitTypeInsn(NEW, MISSING_PROPERTY_EXCEPTION_TYPE);
        methodVisitor.visitInsn(DUP);
        putFirstMethodArgumentOnStack(methodVisitor);
        putClassOnStack(methodVisitor, managedTypeClass);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, MISSING_PROPERTY_EXCEPTION_TYPE, "<init>", MISSING_PROPERTY_CONSTRUCTOR_DESCRIPTOR, false);
        finishVisitingMethod(methodVisitor, ATHROW);

        // Object propertyMissing(String name, Object value)

        methodVisitor = declareMethod(visitor, "propertyMissing", SET_PROPERTY_MISSING_METHOD_DESCRIPTOR, CONCRETE_SIGNATURE);

        // throw new MissingPropertyException(name, <managed-type>.class)
        methodVisitor.visitTypeInsn(NEW, MISSING_PROPERTY_EXCEPTION_TYPE);
        methodVisitor.visitInsn(DUP);
        putFirstMethodArgumentOnStack(methodVisitor);
        putClassOnStack(methodVisitor, managedTypeClass);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, MISSING_PROPERTY_EXCEPTION_TYPE, "<init>", MISSING_PROPERTY_CONSTRUCTOR_DESCRIPTOR, false);
        finishVisitingMethod(methodVisitor, ATHROW);

        // Object methodMissing(String name, Object args)
        methodVisitor = declareMethod(visitor, "methodMissing", METHOD_MISSING_METHOD_DESCRIPTOR, CONCRETE_SIGNATURE);

        // throw new MissingMethodException(name, <managed-type>.class, args)
        methodVisitor.visitTypeInsn(NEW, MISSING_METHOD_EXCEPTION_TYPE);
        methodVisitor.visitInsn(DUP);
        putMethodArgumentOnStack(methodVisitor, 1);
        putClassOnStack(methodVisitor, managedTypeClass);
        putMethodArgumentOnStack(methodVisitor, 2);
        methodVisitor.visitTypeInsn(CHECKCAST, OBJECT_ARRAY_TYPE);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, MISSING_METHOD_EXCEPTION_TYPE, "<init>", MISSING_METHOD_EXCEPTION_CONSTRUCTOR_DESCRIPTOR, false);
        finishVisitingMethod(methodVisitor, ATHROW);
    }

    private void putClassOnStack(MethodVisitor methodVisitor, Class<?> managedTypeClass) {
        putConstantOnStack(methodVisitor, managedTypeClass.getName());
        methodVisitor.visitMethodInsn(INVOKESTATIC, CLASS_INTERNAL_NAME, "forName", FOR_NAME_METHOD_DESCRIPTOR, false);
    }

    private void writeGeneratedViewMethods(ClassWriter visitor, Type generatedType) {
        MethodVisitor methodVisitor = declareMethod(visitor, "__view_state__", GET_VIEW_STATE_METHOD_DESCRIPTOR, CONCRETE_SIGNATURE, ACC_PUBLIC | ACC_SYNTHETIC);
        putStateFieldValueOnStack(methodVisitor, generatedType);
        finishVisitingMethod(methodVisitor, ARETURN);
    }

    private void writeManagedInstanceMethods(ClassVisitor visitor, Type generatedType) {
        writeManagedInstanceGetBackingNodeMethod(visitor, generatedType);
        writeManagedInstanceGetManagedTypeMethod(visitor, generatedType);
    }

    private void writeManagedInstanceGetBackingNodeMethod(ClassVisitor visitor, Type generatedType) {
        MethodVisitor methodVisitor = declareMethod(visitor, "getBackingNode", GET_BACKING_NODE_METHOD_DESCRIPTOR, CONCRETE_SIGNATURE, ACC_PUBLIC | ACC_SYNTHETIC);
        putNodeStateFieldValueOnStack(methodVisitor, generatedType);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, MODEL_ELEMENT_STATE_TYPE_INTERNAL_NAME, "getBackingNode", GET_BACKING_NODE_METHOD_DESCRIPTOR, true);
        finishVisitingMethod(methodVisitor, ARETURN);
    }

    private void writeManagedInstanceGetManagedTypeMethod(ClassVisitor visitor, Type generatedType) {
        MethodVisitor managedTypeVisitor = declareMethod(visitor, "getManagedType", GET_MANAGED_TYPE_METHOD_DESCRIPTOR, CONCRETE_SIGNATURE, ACC_PUBLIC | ACC_SYNTHETIC);
        putManagedTypeFieldValueOnStack(managedTypeVisitor, generatedType);
        finishVisitingMethod(managedTypeVisitor, ARETURN);
    }

    private void assignStateField(MethodVisitor constructorVisitor, Type generatedType) {
        putThisOnStack(constructorVisitor);
        putFirstMethodArgumentOnStack(constructorVisitor);
        constructorVisitor.visitFieldInsn(PUTFIELD, generatedType.getInternalName(), STATE_FIELD_NAME, GENERATED_VIEW_STATE_TYPE.getDescriptor());
    }

    private void assignTypeConverterField(MethodVisitor constructorVisitor, Type generatedType) {
        putThisOnStack(constructorVisitor);
        putSecondMethodArgumentOnStack(constructorVisitor);
        constructorVisitor.visitFieldInsn(PUTFIELD, generatedType.getInternalName(), TYPE_CONVERTER_FIELD_NAME, TYPE_CONVERTER_TYPE.getDescriptor());
    }

    private void assignDelegateField(MethodVisitor constructorVisitor, Type generatedType, Type delegateType) {
        putThisOnStack(constructorVisitor);
        putThirdMethodArgumentOnStack(constructorVisitor);
        constructorVisitor.visitFieldInsn(PUTFIELD, generatedType.getInternalName(), DELEGATE_FIELD_NAME, delegateType.getDescriptor());
    }

    private void setCanCallSettersField(MethodVisitor methodVisitor, Type generatedType, boolean canCallSetters) {
        putThisOnStack(methodVisitor);
        methodVisitor.visitLdcInsn(canCallSetters);
        methodVisitor.visitFieldInsn(PUTFIELD, generatedType.getInternalName(), CAN_CALL_SETTERS_FIELD_NAME, Type.BOOLEAN_TYPE.getDescriptor());
    }

    private void writeViewMethods(ClassVisitor visitor, Type generatedType, NewStructSchema<?> viewSchema, ManagedStructBindingStore.ManagedStructBinding bindings) {
        NewStructSchema<?> delegateSchema = bindings.getDelegateSchema();
        if (delegateSchema != null) {
            declareDelegateField(visitor, delegateSchema);
        }
        Class<?> viewClass = viewSchema.getType().getConcreteClass();
        Set<WeaklyTypeReferencingMethod<?, ?>> viewSchemaMethods = viewSchema.getAllMethods();
        // TODO:LPTR What is the "metaClass" property?
        for (NewModelProperty<?> property : viewSchema.getProperties()) {
            writeConfigureMethod(visitor, generatedType, property);
            writeSetMethod(visitor, generatedType, property);
            createTypeConvertingSetter(visitor, generatedType, property);

            // Do not create managed property if bindings says it should not be generated
            if (bindings.getGeneratedProperties().containsKey(property.getName())) {
                writeGetters(visitor, generatedType, property);
                writeSetter(visitor, generatedType, property);
            }
        }
        for (WeaklyTypeReferencingMethod<?, ?> viewMethod : bindings.getViewBindings()) {
            // Don't generate method if it's not part of the view schema
            if (!viewSchemaMethods.contains(viewMethod)) {
                continue;
            }
            Method method = viewMethod.getMethod();
            if (!Modifier.isFinal(method.getModifiers())) {
                writeNonAbstractMethodWrapper(visitor, generatedType, viewClass, method);
            }
        }
        if (delegateSchema != null) {
            Class<?> delegateClass = delegateSchema.getType().getConcreteClass();
            Type delegateType = Type.getType(delegateClass);
            for (ManagedStructBindingStore.DelegateMethodBinding delegateBinding : bindings.getDelegateBindings()) {
                // Don't generate method if it's not part of the view schema
                if (!viewSchemaMethods.contains(delegateBinding.getSource())) {
                    continue;
                }
                writeDelegatedMethod(visitor, generatedType, delegateType, delegateBinding.getTarget().getMethod());
            }
        }
    }

    private void writeSetMethod(ClassVisitor visitor, Type generatedType, NewModelProperty<?> property) {
        if (property.isWritable() && property.getSchema() instanceof ScalarValueSchema) {

            // TODO - should we support this?
            // Adds a void $propName(Object value) method that sets the value
            MethodVisitor methodVisitor = declareMethod(visitor, property.getName(), Type.getMethodDescriptor(Type.VOID_TYPE, OBJECT_TYPE), null);
            putThisOnStack(methodVisitor);
            putFirstMethodArgumentOnStack(methodVisitor);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, generatedType.getInternalName(), property.getAccessor(SETTER).getName(), Type.getMethodDescriptor(Type.VOID_TYPE, OBJECT_TYPE), false);
            finishVisitingMethod(methodVisitor);
        }
    }

    private void writeConfigureMethod(ClassVisitor visitor, Type generatedType, NewModelProperty<?> property) {
        if (!property.isWritable() && property.getSchema() instanceof CompositeSchema) {
            // Adds a void $propName(Closure<?> cl) method that delegates to model state

            MethodVisitor methodVisitor = declareMethod(visitor, property.getName(), Type.getMethodDescriptor(Type.VOID_TYPE, CLOSURE_TYPE), null);
            putNodeStateFieldValueOnStack(methodVisitor, generatedType);
            putConstantOnStack(methodVisitor, property.getName());
            putFirstMethodArgumentOnStack(methodVisitor);
            methodVisitor.visitMethodInsn(INVOKEINTERFACE, MODEL_ELEMENT_STATE_TYPE_INTERNAL_NAME, "apply", STATE_APPLY_METHOD_DESCRIPTOR, true);
            finishVisitingMethod(methodVisitor);
            return;
        }
        if (!property.isWritable() && property.getSchema() instanceof NewStructSchema) {
            NewStructSchema<?> structSchema = (NewStructSchema<?>) property.getSchema();
            if (!structSchema.isAnnotated()) {
                return;
            }
            WeaklyTypeReferencingMethod<?, ?> getter = property.getAccessor(GET_GETTER);
            if (getter == null) {
                getter = property.getAccessor(IS_GETTER);
            }

            // Adds a void $propName(Closure<?> cl) method that executes the closure
            MethodVisitor methodVisitor = declareMethod(visitor, property.getName(), Type.getMethodDescriptor(Type.VOID_TYPE, CLOSURE_TYPE), null);
            putThisOnStack(methodVisitor);
            methodVisitor.visitMethodInsn(INVOKEVIRTUAL, generatedType.getInternalName(), getter.getName(), Type.getMethodDescriptor(Type.getType(property.getType().getConcreteClass())), false);
            putFirstMethodArgumentOnStack(methodVisitor);
            methodVisitor.visitMethodInsn(INVOKESTATIC, Type.getInternalName(ClosureBackedAction.class), "execute", Type.getMethodDescriptor(Type.VOID_TYPE, OBJECT_TYPE, CLOSURE_TYPE), false);
            finishVisitingMethod(methodVisitor);
            return;
        }

        // Adds a void $propName(Closure<?> cl) method that throws MME, to avoid attempts to convert closure to something else
        MethodVisitor methodVisitor = declareMethod(visitor, property.getName(), Type.getMethodDescriptor(Type.VOID_TYPE, CLOSURE_TYPE), null);
        putThisOnStack(methodVisitor);
        putConstantOnStack(methodVisitor, property.getName());
        methodVisitor.visitInsn(Opcodes.ICONST_1);
        methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_TYPE.getInternalName());
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitInsn(Opcodes.ICONST_0);
        putFirstMethodArgumentOnStack(methodVisitor);
        methodVisitor.visitInsn(Opcodes.AASTORE);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, generatedType.getInternalName(), "methodMissing", METHOD_MISSING_METHOD_DESCRIPTOR, false);
        finishVisitingMethod(methodVisitor);
    }

    private void writeSetter(ClassVisitor visitor, Type generatedType, NewModelProperty<?> property) {
        WeaklyTypeReferencingMethod<?, ?> weakSetter = property.getAccessor(SETTER);
        // There is no setter for this property
        if (weakSetter == null) {
            return;
        }

        String propertyName = property.getName();
        Class<?> propertyClass = property.getType().getConcreteClass();
        Type propertyType = Type.getType(propertyClass);
        Label calledOutsideOfConstructor = new Label();

        Method setter = weakSetter.getMethod();

        // the regular typed setter
        String methodDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, propertyType);
        MethodVisitor methodVisitor = declareMethod(visitor, setter.getName(), methodDescriptor, AsmClassGeneratorUtils.signature(setter));

        putCanCallSettersFieldValueOnStack(methodVisitor, generatedType);
        jumpToLabelIfStackEvaluatesToTrue(methodVisitor, calledOutsideOfConstructor);
        throwExceptionBecauseCalledOnItself(methodVisitor);

        methodVisitor.visitLabel(calledOutsideOfConstructor);
        putNodeStateFieldValueOnStack(methodVisitor, generatedType);
        putConstantOnStack(methodVisitor, propertyName);
        putFirstMethodArgumentOnStack(methodVisitor, propertyType);
        if (propertyClass.isPrimitive()) {
            boxType(methodVisitor, propertyClass);
        }
        invokeStateSetMethod(methodVisitor);

        finishVisitingMethod(methodVisitor);
    }

    // the overload of type Object for Groovy coercions:  public void setFoo(Object foo)
    private void createTypeConvertingSetter(ClassVisitor visitor, Type generatedType, NewModelProperty<?> property) {
        if (!property.isWritable() || !(property.getSchema() instanceof ScalarValueSchema)) {
            return;
        }

        Class<?> propertyClass = property.getType().getConcreteClass();
        Type propertyType = Type.getType(propertyClass);
        Class<?> boxedClass = propertyClass.isPrimitive() ? BOXED_TYPES.get(propertyClass) : propertyClass;
        Type boxedType = Type.getType(boxedClass);

        Method setter = property.getAccessor(SETTER).getMethod();
        MethodVisitor methodVisitor = declareMethod(visitor, setter.getName(), SET_OBJECT_PROPERTY_DESCRIPTOR, SET_OBJECT_PROPERTY_DESCRIPTOR);

        putThisOnStack(methodVisitor);
        putTypeConverterFieldValueOnStack(methodVisitor, generatedType);

        // Object converted = $typeConverter.convert(foo, Float.class, false);
        methodVisitor.visitVarInsn(ALOAD, 1); // put var #1 ('foo') on the stack
        methodVisitor.visitLdcInsn(boxedType); // push the constant Class onto the stack
        methodVisitor.visitInsn(propertyClass.isPrimitive() ? ICONST_1 : ICONST_0); // push int 1 or 0 (interpreted as true or false) onto the stack
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, TYPE_CONVERTER_TYPE.getInternalName(), "convert", COERCE_TO_SCALAR_DESCRIPTOR, true);
        methodVisitor.visitTypeInsn(CHECKCAST, boxedType.getInternalName());

        if (propertyClass.isPrimitive()) {
            unboxType(methodVisitor, propertyClass);
        }

        // invoke the typed setter, popping 'this' and 'converted' from the stack
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, generatedType.getInternalName(), setter.getName(), Type.getMethodDescriptor(Type.VOID_TYPE, propertyType), false);
        finishVisitingMethod(methodVisitor);
    }

    private void writeHashCodeMethod(ClassVisitor visitor, Type generatedType) {
        MethodVisitor methodVisitor = declareMethod(visitor, "hashCode", HASH_CODE_METHOD_DESCRIPTOR, null);
        putStateFieldValueOnStack(methodVisitor, generatedType);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, GENERATED_VIEW_STATE_TYPE.getInternalName(), "hashCode", HASH_CODE_METHOD_DESCRIPTOR, true);
        finishVisitingMethod(methodVisitor, Opcodes.IRETURN);
    }

    private void writeEqualsMethod(ClassVisitor cw, Type generatedType) {
        MethodVisitor methodVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", EQUALS_METHOD_DESCRIPTOR, null, null);
        methodVisitor.visitCode();

        // if (arg == this) { return true; }
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        Label notSameLabel = new Label();
        methodVisitor.visitJumpInsn(IF_ACMPNE, notSameLabel);
        methodVisitor.visitInsn(ICONST_1);
        methodVisitor.visitInsn(IRETURN);

        // if (!(age instanceof GeneratedView)) { return false; }
        methodVisitor.visitLabel(notSameLabel);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitTypeInsn(INSTANCEOF, GENERATED_VIEW_TYPE.getInternalName());
        Label generatedViewLabel = new Label();
        methodVisitor.visitJumpInsn(IFNE, generatedViewLabel);
        methodVisitor.visitInsn(ICONST_0);
        methodVisitor.visitInsn(IRETURN);

        // return state.equals(((GeneratedView)arg).__view_state());
        methodVisitor.visitLabel(generatedViewLabel);
        putStateFieldValueOnStack(methodVisitor, generatedType);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitTypeInsn(CHECKCAST, GENERATED_VIEW_TYPE.getInternalName());
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, GENERATED_VIEW_TYPE.getInternalName(), "__view_state__", GET_VIEW_STATE_METHOD_DESCRIPTOR, true);
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, GENERATED_VIEW_STATE_TYPE.getInternalName(), "equals", EQUALS_METHOD_DESCRIPTOR, true);
        finishVisitingMethod(methodVisitor, Opcodes.IRETURN);
    }

    private void throwExceptionBecauseCalledOnItself(MethodVisitor methodVisitor) {
        String exceptionInternalName = Type.getInternalName(UnsupportedOperationException.class);
        methodVisitor.visitTypeInsn(NEW, exceptionInternalName);
        methodVisitor.visitInsn(DUP);
        putConstantOnStack(methodVisitor, "Calling setters of a managed type on itself is not allowed");

        String constructorDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, STRING_TYPE);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, exceptionInternalName, CONSTRUCTOR_NAME, constructorDescriptor, false);
        methodVisitor.visitInsn(ATHROW);
    }

    private void jumpToLabelIfStackEvaluatesToTrue(MethodVisitor methodVisitor, Label label) {
        methodVisitor.visitJumpInsn(IFNE, label);
    }

    private void invokeStateSetMethod(MethodVisitor methodVisitor) {
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, MODEL_ELEMENT_STATE_TYPE_INTERNAL_NAME, "set", STATE_SET_METHOD_DESCRIPTOR, true);
    }

    private void putConstantOnStack(MethodVisitor methodVisitor, Object value) {
        methodVisitor.visitLdcInsn(value);
    }

    private MethodVisitor declareMethod(ClassVisitor visitor, Method method) {
        return declareMethod(visitor, method.getName(), Type.getMethodDescriptor(method));
    }

    private MethodVisitor declareMethod(ClassVisitor visitor, String methodName, String methodDescriptor) {
        return declareMethod(visitor, methodName, methodDescriptor, CONCRETE_SIGNATURE);
    }

    private MethodVisitor declareMethod(ClassVisitor visitor, String methodName, String methodDescriptor, String methodSignature) {
        return declareMethod(visitor, methodName, methodDescriptor, methodSignature, ACC_PUBLIC);
    }

    private MethodVisitor declareMethod(ClassVisitor visitor, String methodName, String methodDescriptor, String methodSignature, int access) {
        MethodVisitor methodVisitor = visitor.visitMethod(access, methodName, methodDescriptor, methodSignature, NO_EXCEPTIONS);
        methodVisitor.visitCode();
        return methodVisitor;
    }

    private void putFirstMethodArgumentOnStack(MethodVisitor methodVisitor, Type argType) {
        int loadCode = argType.getOpcode(ILOAD);
        methodVisitor.visitVarInsn(loadCode, 1);
    }

    private void putFirstMethodArgumentOnStack(MethodVisitor methodVisitor) {
        putFirstMethodArgumentOnStack(methodVisitor, OBJECT_TYPE);
    }

    private void putSecondMethodArgumentOnStack(MethodVisitor methodVisitor) {
        methodVisitor.visitVarInsn(ALOAD, 2);
    }

    private void putThirdMethodArgumentOnStack(MethodVisitor methodVisitor) {
        methodVisitor.visitVarInsn(ALOAD, 3);
    }

    private void putMethodArgumentOnStack(MethodVisitor methodVisitor, int index) {
        methodVisitor.visitVarInsn(ALOAD, index);
    }

    private void putMethodArgumentOnStack(MethodVisitor methodVisitor, Type type, int index) {
        methodVisitor.visitVarInsn(type.getOpcode(ILOAD), index);
    }

    private void putStateFieldValueOnStack(MethodVisitor methodVisitor, Type generatedType) {
        putFieldValueOnStack(methodVisitor, generatedType, STATE_FIELD_NAME, GENERATED_VIEW_STATE_TYPE);
    }

    private void putNodeStateFieldValueOnStack(MethodVisitor methodVisitor, Type generatedType) {
        putFieldValueOnStack(methodVisitor, generatedType, STATE_FIELD_NAME, GENERATED_VIEW_STATE_TYPE);
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, MODEL_ELEMENT_STATE_TYPE_INTERNAL_NAME);
    }

    private void putTypeConverterFieldValueOnStack(MethodVisitor methodVisitor, Type generatedType) {
        putFieldValueOnStack(methodVisitor, generatedType, TYPE_CONVERTER_FIELD_NAME, TYPE_CONVERTER_TYPE);
    }

    private void putManagedTypeFieldValueOnStack(MethodVisitor methodVisitor, Type generatedType) {
        putStaticFieldValueOnStack(methodVisitor, generatedType, MANAGED_TYPE_FIELD_NAME, MODEL_TYPE_TYPE);
    }

    private void putDelegateFieldValueOnStack(MethodVisitor methodVisitor, Type generatedType, Type delegateType) {
        putFieldValueOnStack(methodVisitor, generatedType, DELEGATE_FIELD_NAME, delegateType);
    }

    private void putCanCallSettersFieldValueOnStack(MethodVisitor methodVisitor, Type generatedType) {
        putFieldValueOnStack(methodVisitor, generatedType, CAN_CALL_SETTERS_FIELD_NAME, Type.BOOLEAN_TYPE);
    }

    private void putFieldValueOnStack(MethodVisitor methodVisitor, Type generatedType, String name, Type fieldType) {
        putThisOnStack(methodVisitor);
        methodVisitor.visitFieldInsn(GETFIELD, generatedType.getInternalName(), name, fieldType.getDescriptor());
    }

    private void putStaticFieldValueOnStack(MethodVisitor methodVisitor, Type generatedType, String name, Type fieldType) {
        methodVisitor.visitFieldInsn(GETSTATIC, generatedType.getInternalName(), name, fieldType.getDescriptor());
    }

    private void writeGetters(ClassVisitor visitor, Type generatedType, NewModelProperty<?> property) {
        Class<?> propertyClass = property.getType().getConcreteClass();
        String propertyName = property.getName();
        writeGetter(visitor, generatedType, propertyName, propertyClass, property.getAccessor(GET_GETTER));
        writeGetter(visitor, generatedType, propertyName, propertyClass, property.getAccessor(IS_GETTER));
    }

    private void writeGetter(ClassVisitor visitor, Type generatedType, String propertyName, Class<?> propertyClass, WeaklyTypeReferencingMethod<?, ?> weakGetter) {
        if (weakGetter == null) {
            return;
        }
        Method getter = weakGetter.getMethod();
        Type propertyType = Type.getType(propertyClass);
        MethodVisitor methodVisitor = declareMethod(
            visitor,
            getter.getName(),
            Type.getMethodDescriptor(propertyType),
            AsmClassGeneratorUtils.signature(getter));

        putNodeStateFieldValueOnStack(methodVisitor, generatedType);
        putConstantOnStack(methodVisitor, propertyName);
        invokeStateGetMethod(methodVisitor);
        castFirstStackElement(methodVisitor, propertyClass);
        finishVisitingMethod(methodVisitor, returnCode(propertyType));
    }

    private int returnCode(Type returnType) {
        return returnType.getOpcode(IRETURN);
    }

    private void castFirstStackElement(MethodVisitor methodVisitor, Class<?> returnType) {
        if (returnType.isPrimitive()) {
            unboxType(methodVisitor, returnType);
        } else {
            methodVisitor.visitTypeInsn(CHECKCAST, Type.getInternalName(returnType));
        }
    }

    private void boxType(MethodVisitor methodVisitor, Class<?> primitiveType) {
        Class<?> boxedType = BOXED_TYPES.get(primitiveType);
        methodVisitor.visitMethodInsn(INVOKESTATIC, Type.getInternalName(boxedType), "valueOf", "(" + Type.getDescriptor(primitiveType) + ")" + Type.getDescriptor(boxedType), false);
    }

    private void unboxType(MethodVisitor methodVisitor, Class<?> primitiveClass) {
        // Float f = (Float) tmp
        // f==null?0:f.floatValue()
        Class<?> boxedType = BOXED_TYPES.get(primitiveClass);
        Type primitiveType = Type.getType(primitiveClass);
        methodVisitor.visitTypeInsn(CHECKCAST, Type.getInternalName(boxedType));
        methodVisitor.visitInsn(DUP);
        Label exit = new Label();
        Label elseValue = new Label();
        methodVisitor.visitJumpInsn(IFNONNULL, elseValue);
        methodVisitor.visitInsn(POP);
        pushDefaultValue(methodVisitor, primitiveClass);
        methodVisitor.visitJumpInsn(GOTO, exit);
        methodVisitor.visitLabel(elseValue);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(boxedType), primitiveClass.getSimpleName() + "Value", Type.getMethodDescriptor(primitiveType), false);
        methodVisitor.visitLabel(exit);
    }

    private void pushDefaultValue(MethodVisitor methodVisitor, Class<?> primitiveType) {
        int ins = ICONST_0;
        if (long.class == primitiveType) {
            ins = LCONST_0;
        } else if (double.class == primitiveType) {
            ins = DCONST_0;
        } else if (float.class == primitiveType) {
            ins = FCONST_0;
        }
        methodVisitor.visitInsn(ins);
    }

    private void invokeStateGetMethod(MethodVisitor methodVisitor) {
        methodVisitor.visitMethodInsn(INVOKEINTERFACE, MODEL_ELEMENT_STATE_TYPE_INTERNAL_NAME, "get", STATE_GET_METHOD_DESCRIPTOR, true);
    }

    private void writeNonAbstractMethodWrapper(ClassVisitor visitor, Type generatedType, Class<?> managedTypeClass, Method method) {
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();

        MethodVisitor methodVisitor = declareMethod(visitor, method);

        methodVisitor.visitTryCatchBlock(start, end, handler, null);

        setCanCallSettersField(methodVisitor, generatedType, false);

        methodVisitor.visitLabel(start);
        invokeSuperMethod(methodVisitor, managedTypeClass, method);
        methodVisitor.visitLabel(end);

        setCanCallSettersField(methodVisitor, generatedType, true);
        methodVisitor.visitInsn(ARETURN);

        methodVisitor.visitLabel(handler);
        setCanCallSettersField(methodVisitor, generatedType, true);
        methodVisitor.visitInsn(ATHROW);

        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    private void writeDelegatedMethod(ClassVisitor visitor, Type generatedType, Type delegateType, Method method) {
        MethodVisitor methodVisitor = declareMethod(visitor, method.getName(), Type.getMethodDescriptor(method), AsmClassGeneratorUtils.signature(method));
        invokeDelegateMethod(methodVisitor, generatedType, delegateType, method);
        Class<?> returnType = method.getReturnType();
        finishVisitingMethod(methodVisitor, returnCode(Type.getType(returnType)));
    }

    private void invokeDelegateMethod(MethodVisitor methodVisitor, Type generatedType, Type delegateType, Method method) {
        putDelegateFieldValueOnStack(methodVisitor, generatedType, delegateType);
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int paramNo = 0; paramNo < parameterTypes.length; paramNo++) {
            putMethodArgumentOnStack(methodVisitor, Type.getType(parameterTypes[paramNo]), paramNo + 1);
        }
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, delegateType.getInternalName(), method.getName(), Type.getMethodDescriptor(method), false);
    }

    private void invokeSuperMethod(MethodVisitor methodVisitor, Class<?> superClass, Method method) {
        putThisOnStack(methodVisitor);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(superClass), method.getName(), Type.getMethodDescriptor(method), false);
    }

    public interface GeneratedView {
        GeneratedViewState __view_state__();
    }
}
