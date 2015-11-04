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

package org.gradle.jvm.tasks.api;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.gradle.internal.Factory;
import org.objectweb.asm.*;

import java.util.List;
import java.util.Set;

class ApiMemberExtractor extends ClassVisitor implements Opcodes {

    private final List<MethodSig> methods = Lists.newLinkedList();
    private final List<FieldSig> fields = Lists.newLinkedList();
    private final List<InnerClassSig> innerClasses = Lists.newLinkedList();
    private final StubClassWriter adapter;
    private final boolean hasDeclaredApi;
    private final ApiValidator apiValidator;

    private String internalClassName;
    private boolean isInnerClass;
    private ClassSig classSig;

    public ApiMemberExtractor(StubClassWriter cv, boolean hasDeclaredApi, ApiValidator apiValidator) {
        super(ASM5);
        this.adapter = cv;
        this.hasDeclaredApi = hasDeclaredApi;
        this.apiValidator = apiValidator;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        classSig = new ClassSig(version, access, name, signature, superName, interfaces);
        internalClassName = name;
        isInnerClass = (access & ACC_SUPER) == ACC_SUPER;
        apiValidator.validateSuperTypes(name, signature, superName, interfaces);
    }

    @Override
    public void visitEnd() {
        super.visitEnd();

        adapter.visit(classSig.getVersion(), classSig.getAccess(), classSig.getName(), classSig.getSignature(), classSig.getSuperName(), classSig.getInterfaces());
        visitAnnotationSigs(Sets.newTreeSet(classSig.getAnnotations()));
        for (MethodSig method : Sets.newTreeSet(methods)) {
            MethodVisitor mv = adapter.visitMethod(method.getAccess(), method.getName(), method.getDesc(), method.getSignature(), method.getExceptions().toArray(new String[method.getExceptions().size()]));
            visitAnnotationSigs(mv, Sets.newTreeSet(method.getAnnotations()));
            visitAnnotationSigs(mv, Sets.newTreeSet(method.getParameterAnnotations()));
            mv.visitEnd();
        }
        for (FieldSig field : Sets.newTreeSet(fields)) {
            FieldVisitor fieldVisitor = adapter.visitField(field.getAccess(), field.getName(), field.getDesc(), field.getSignature(), null);
            visitAnnotationSigs(fieldVisitor, Sets.newTreeSet(field.getAnnotations()));
            fieldVisitor.visitEnd();
        }
        for (InnerClassSig innerClass : Sets.newTreeSet(innerClasses)) {
            adapter.visitInnerClass(innerClass.getName(), innerClass.getOuterName(), innerClass.getInnerName(), innerClass.getAccess());
        }
        adapter.visitEnd();
    }

    private void visitAnnotationSigs(Set<AnnotationSig> annotationSigs) {
        for (AnnotationSig annotation : annotationSigs) {
            AnnotationVisitor annotationVisitor = adapter.visitAnnotation(annotation.getName(), annotation.isVisible());
            visitAnnotationValues(annotation, annotationVisitor);
        }
    }

    private void visitAnnotationSigs(MethodVisitor mv, Set<AnnotationSig> annotationSigs) {
        for (AnnotationSig annotation : annotationSigs) {
            AnnotationVisitor annotationVisitor;
            if (annotation instanceof ParameterAnnotationSig) {
                annotationVisitor = mv.visitParameterAnnotation(((ParameterAnnotationSig) annotation).getParameter(), annotation.getName(), annotation.isVisible());
            } else {
                annotationVisitor = mv.visitAnnotation(annotation.getName(), annotation.isVisible());
            }
            visitAnnotationValues(annotation, annotationVisitor);
        }
    }

    private void visitAnnotationSigs(FieldVisitor fv, Set<AnnotationSig> annotationSigs) {
        for (AnnotationSig annotation : annotationSigs) {
            AnnotationVisitor annotationVisitor = fv.visitAnnotation(annotation.getName(), annotation.isVisible());
            visitAnnotationValues(annotation, annotationVisitor);
        }
    }

    private void visitAnnotationValues(AnnotationSig annotation, AnnotationVisitor annotationVisitor) {
        Set<AnnotationValue> values = Sets.newTreeSet(annotation.getValues());
        for (AnnotationValue value : values) {
            visitAnnotationValue(annotationVisitor, value);
        }
        annotationVisitor.visitEnd();
    }

    private void visitAnnotationValue(AnnotationVisitor annotationVisitor, AnnotationValue value) {
        String name = value.getName();
        if (value instanceof EnumAnnotationValue) {
            annotationVisitor.visitEnum(name, ((EnumAnnotationValue) value).getDesc(), (String) ((EnumAnnotationValue) value).getValue());
        } else if (value instanceof SimpleAnnotationValue) {
            annotationVisitor.visit(name, ((SimpleAnnotationValue) value).getValue());
        } else if (value instanceof ArrayAnnotationValue) {
            AnnotationVisitor arrayVisitor = annotationVisitor.visitArray(name);
            AnnotationValue[] values = ((ArrayAnnotationValue) value).getValue();
            for (AnnotationValue annotationValue : values) {
                visitAnnotationValue(arrayVisitor, annotationValue);
            }
            arrayVisitor.visitEnd();
        } else if (value instanceof AnnotationAnnotationValue) {
            AnnotationSig annotation = ((AnnotationAnnotationValue) value).getAnnotation();
            AnnotationVisitor annVisitor = annotationVisitor.visitAnnotation(name, annotation.getName());
            visitAnnotationValues(annotation, annVisitor);
        }
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
        return apiValidator.validateAnnotation(AsmUtils.convertInternalNameToClassName(internalClassName), desc, new Factory<AnnotationVisitor>() {
            @Override
            public AnnotationVisitor create() {
                final AnnotationSig sig = classSig.addAnnotation(desc, visible);
                return new SortingAnnotationVisitor(sig, ApiMemberExtractor.super.visitAnnotation(desc, visible));
            }
        });
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
        if ("<clinit>".equals(name)) {
            // discard static initializers
            return null;
        }
        if (isApiMember(access) || ("<init>".equals(name) && isInnerClass)) {
            final MethodSig methodSig = new MethodSig(access, name, desc, signature, exceptions);
            return apiValidator.validateMethod(methodSig, new Factory<MethodVisitor>() {
                @Override
                public MethodVisitor create() {
                    methods.add(methodSig);
                    return createMethodAnnotationChecker(methodSig);
                }
            });
        }
        return null;
    }

    private MethodVisitor createMethodAnnotationChecker(final MethodSig methodSig) {
        return new MethodVisitor(Opcodes.ASM5) {

            private AnnotationVisitor superVisitParameterAnnotation(int parameter, String annDesc, boolean visible) {
                return super.visitParameterAnnotation(parameter, annDesc, visible);
            }

            @Override
            public AnnotationVisitor visitAnnotation(final String annDesc, final boolean visible) {
                return apiValidator.validateAnnotation(methodSig.toString(), annDesc, new Factory<AnnotationVisitor>() {
                    @Override
                    public AnnotationVisitor create() {
                        AnnotationSig sig = methodSig.addAnnotation(annDesc, visible);
                        return new SortingAnnotationVisitor(sig, ApiMemberExtractor.super.visitAnnotation(annDesc, visible));
                    }
                });

            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(final int parameter, final String annDesc, final boolean visible) {
                return apiValidator.validateAnnotation(methodSig.toString(), annDesc, new Factory<AnnotationVisitor>() {
                    @Override
                    public AnnotationVisitor create() {
                        ParameterAnnotationSig pSig = methodSig.addParameterAnnotation(parameter, annDesc, visible);
                        return new SortingAnnotationVisitor(pSig, superVisitParameterAnnotation(parameter, annDesc, visible));
                    }
                });
            }
        };
    }

    @Override
    public FieldVisitor visitField(final int access, final String name, final String desc, final String signature, Object value) {
        if (isApiMember(access)) {
            final FieldSig fieldSig = new FieldSig(access, name, desc, signature);
            return apiValidator.validateField(fieldSig, new Factory<FieldVisitor>() {
                @Override
                public FieldVisitor create() {
                    fields.add(fieldSig);
                    return new FieldVisitor(Opcodes.ASM5) {
                        private AnnotationVisitor superVisitAnnotation(String desc, boolean visible) {
                            return super.visitAnnotation(desc, visible);
                        }

                        @Override
                        public AnnotationVisitor visitAnnotation(final String annotationDesc, final boolean visible) {
                            return apiValidator.validateAnnotation(fieldSig.toString(), annotationDesc, new Factory<AnnotationVisitor>() {
                                @Override
                                public AnnotationVisitor create() {
                                    AnnotationSig sig = fieldSig.addAnnotation(annotationDesc, visible);
                                    return new SortingAnnotationVisitor(sig, superVisitAnnotation(annotationDesc, visible));
                                }
                            });

                        }
                    };
                }
            });

        }
        return null;
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        if (innerName == null) {
            return;
        }
        if (isPackagePrivateMember(access) && hasDeclaredApi) {
            return;
        }
        innerClasses.add(new InnerClassSig(name, outerName, innerName, access));
        super.visitInnerClass(name, outerName, innerName, access);
    }

    public static boolean isApiMember(int access, boolean hasDeclaredApi) {
        return (isPackagePrivateMember(access) && !hasDeclaredApi) || isPublicMember(access) || isProtectedMember(access);
    }

    private boolean isApiMember(int access) {
        return (isPackagePrivateMember(access) && !hasDeclaredApi) || isPublicMember(access) || isProtectedMember(access);
    }

    private static boolean isPublicMember(int access) {
        return (access & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC;
    }

    private static boolean isProtectedMember(int access) {
        return (access & Opcodes.ACC_PROTECTED) == Opcodes.ACC_PROTECTED;
    }

    private static boolean isPackagePrivateMember(int access) {
        return access == 0
            || access == Opcodes.ACC_STATIC
            || access == Opcodes.ACC_SUPER
            || access == (Opcodes.ACC_SUPER | Opcodes.ACC_STATIC);
    }
}