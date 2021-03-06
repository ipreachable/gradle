/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.groovy.scripts.internal;

import com.google.common.io.Files;
import groovy.lang.Script;
import org.codehaus.groovy.ast.ClassNode;
import org.gradle.api.Action;
import org.gradle.api.internal.changedetection.state.FileSnapshotter;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderCache;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderId;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.CacheValidator;
import org.gradle.cache.PersistentCache;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.UncheckedException;
import org.gradle.logging.ProgressLogger;
import org.gradle.logging.ProgressLoggerFactory;
import org.gradle.model.dsl.internal.transform.RuleVisitor;
import org.objectweb.asm.*;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * A {@link ScriptClassCompiler} which compiles scripts to a cache directory, and loads them from there.
 */
public class FileCacheBackedScriptClassCompiler implements ScriptClassCompiler, Closeable {
    private final ScriptCompilationHandler scriptCompilationHandler;
    private final ProgressLoggerFactory progressLoggerFactory;
    private final CacheRepository cacheRepository;
    private final CacheValidator validator;
    private final FileSnapshotter snapshotter;
    private final ClassLoaderCache classLoaderCache;

    public FileCacheBackedScriptClassCompiler(CacheRepository cacheRepository, CacheValidator validator, ScriptCompilationHandler scriptCompilationHandler,
                                              ProgressLoggerFactory progressLoggerFactory, FileSnapshotter snapshotter, ClassLoaderCache classLoaderCache) {
        this.cacheRepository = cacheRepository;
        this.validator = validator;
        this.scriptCompilationHandler = scriptCompilationHandler;
        this.progressLoggerFactory = progressLoggerFactory;
        this.snapshotter = snapshotter;
        this.classLoaderCache = classLoaderCache;
    }

    @Override
    public <T extends Script, M> CompiledScript<T, M> compile(final ScriptSource source,
                                                              final ClassLoader classLoader,
                                                              final ClassLoaderId classLoaderId,
                                                              final CompileOperation<M> operation,
                                                              final Class<T> scriptBaseClass,
                                                              final Action<? super ClassNode> verifier) {
        assert source.getResource().isContentCached();
        if (source.getResource().getHasEmptyContent()) {
            return emptyCompiledScript(classLoaderId, operation);
        }

        final String sourceHash = hashFor(source);
        final String classpathHash = operation.getCacheKey();
        final String dslId = operation.getId();
        final RemappingScriptSource remapped = new RemappingScriptSource(source);

        // Caching involves 2 distinct caches, so that 2 scripts with the same (hash, classpath) do not get compiled twice
        // 1. First, we look for a cache script which (path, hash) matches. This cache is invalidated when the compile classpath of the script changes
        // 2. Then we look into the 2d cache for a "generic script" with the same hash, that will be remapped to the script class name
        // Both caches can be closed directly after use because:
        // For 1, if the script changes or its compile classpath changes, a different directory will be used
        // For 2, if the script changes, a different cache is used. If the classpath changes, the cache is invalidated, but classes are remapped to 1. anyway so never directly used
        PersistentCache remappedClassesCache = cacheRepository.cache(String.format("scripts-remapped/%s/%s/%s", source.getClassName(), sourceHash, classpathHash))
            .withDisplayName(String.format("%s remapped class cache for %s", dslId, sourceHash))
            .withValidator(validator)
            .withInitializer(new ProgressReportingInitializer(progressLoggerFactory, new RemapBuildScriptsAction<M, T>(remapped, classpathHash, sourceHash, dslId, classLoader, operation, verifier, scriptBaseClass),
                "Compiling script into cache",
                "Compiling " + source.getFileName() + " into local build cache"))
            .open();
        remappedClassesCache.close();

        File remappedClassesDir = classesDir(remappedClassesCache);
        File remappedMetadataDir = metadataDir(remappedClassesCache);

        return scriptCompilationHandler.loadFromDir(source, classLoader, remappedClassesDir, remappedMetadataDir, operation, scriptBaseClass, classLoaderId);
    }

    private <T extends Script, M> CompiledScript<T, M> emptyCompiledScript(ClassLoaderId classLoaderId, CompileOperation<M> operation) {
        classLoaderCache.remove(classLoaderId);
        return new EmptyCompiledScript<T, M>(operation);
    }

    private String hashFor(ScriptSource source) {
        return snapshotter.snapshot(source.getResource()).getHash().asCompactString();
    }

    public void close() {
    }

    private File classesDir(PersistentCache cache) {
        return new File(cache.getBaseDir(), "classes");
    }

    private File metadataDir(PersistentCache cache) {
        return new File(cache.getBaseDir(), "metadata");
    }

    private class CompileToCrossBuildCacheAction implements Action<PersistentCache> {
        private final Action<? super ClassNode> verifier;
        private final Class<? extends Script> scriptBaseClass;
        private final ClassLoader classLoader;
        private final CompileOperation<?> transformer;
        private final ScriptSource source;

        public <T extends Script> CompileToCrossBuildCacheAction(ScriptSource source, ClassLoader classLoader, CompileOperation<?> transformer,
                                                                 Action<? super ClassNode> verifier, Class<T> scriptBaseClass) {
            this.source = source;
            this.classLoader = classLoader;
            this.transformer = transformer;
            this.verifier = verifier;
            this.scriptBaseClass = scriptBaseClass;
        }

        public void execute(PersistentCache cache) {
            File classesDir = classesDir(cache);
            File metadataDir = metadataDir(cache);
            scriptCompilationHandler.compileToDir(source, classLoader, classesDir, metadataDir, transformer, scriptBaseClass, verifier);
        }
    }

    static class ProgressReportingInitializer implements Action<PersistentCache> {
        private ProgressLoggerFactory progressLoggerFactory;
        private Action<? super PersistentCache> delegate;
        private final String shortDescription;
        private final String longDescription;

        public ProgressReportingInitializer(ProgressLoggerFactory progressLoggerFactory,
                                            Action<PersistentCache> delegate,
                                            String shortDescription,
                                            String longDescription) {
            this.progressLoggerFactory = progressLoggerFactory;
            this.delegate = delegate;
            this.shortDescription = shortDescription;
            this.longDescription = longDescription;
        }

        public void execute(PersistentCache cache) {
            ProgressLogger op = progressLoggerFactory.newOperation(FileCacheBackedScriptClassCompiler.class)
                .start(shortDescription, longDescription);
            try {
                delegate.execute(cache);
            } finally {
                op.completed();
            }
        }
    }

    private static class EmptyCompiledScript<T extends Script, M> implements CompiledScript<T, M> {
        private final M data;

        public EmptyCompiledScript(CompileOperation<M> operation) {
            this.data = operation.getExtractedData();
        }

        @Override
        public boolean getRunDoesSomething() {
            return false;
        }

        @Override
        public boolean getHasMethods() {
            return false;
        }

        public Class<? extends T> loadClass() {
            throw new UnsupportedOperationException("Cannot load a script that does nothing.");
        }

        @Override
        public M getData() {
            return data;
        }
    }

    private static class BuildScriptRemapper extends ClassVisitor implements Opcodes {
        private final ScriptSource scriptSource;

        public BuildScriptRemapper(ClassVisitor cv, ScriptSource source) {
            super(ASM5, cv);
            this.scriptSource = source;
        }

        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            cv.visit(version, access, remap(name), remap(signature), remap(superName), remap(interfaces));
        }

        @Override
        public void visitSource(String source, String debug) {
            cv.visitSource(scriptSource.getFileName(), debug);
        }

        private String[] remap(String[] names) {
            if (names == null) {
                return null;
            }
            String[] remapped = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                remapped[i] = remap(names[i]);
            }
            return remapped;
        }

        private String remap(String name) {
            if (name == null) {
                return null;
            }
            return name.replaceAll(RemappingScriptSource.MAPPED_SCRIPT, scriptSource.getClassName());
        }

        private Object remap(Object o) {
            if (o instanceof Type) {
                return Type.getType(remap(((Type) o).getDescriptor()));
            }
            if (RuleVisitor.SOURCE_URI_TOKEN.equals(o)) {
                URI uri = scriptSource.getResource().getLocation().getURI();
                return uri == null ? null : uri.toString();
            }
            if (RuleVisitor.SOURCE_DESC_TOKEN.equals(o)) {
                return scriptSource.getDisplayName();
            }
            return o;
        }

        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, remap(desc), remap(signature), remap(exceptions));
            if (mv != null && (access & ACC_ABSTRACT) == 0) {
                mv = new MethodRenamer(mv);
            }
            return mv;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            return super.visitField(access, name, remap(desc), remap(signature), remap(value));
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            super.visitInnerClass(remap(name), remap(outerName), remap(innerName), access);
        }

        @Override
        public void visitOuterClass(String owner, String name, String desc) {
            super.visitOuterClass(remap(owner), remap(name), remap(desc));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            return super.visitAnnotation(remap(desc), visible);
        }

        class MethodRenamer extends MethodVisitor {

            public MethodRenamer(final MethodVisitor mv) {
                super(ASM5, mv);
            }

            public void visitTypeInsn(int i, String name) {
                mv.visitTypeInsn(i, remap(name));
            }

            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                mv.visitFieldInsn(opcode, remap(owner), name, remap(desc));
            }

            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean intf) {
                mv.visitMethodInsn(opcode, remap(owner), name, remap(desc), intf);
            }

            @Override
            public void visitLdcInsn(Object cst) {
                super.visitLdcInsn(remap(cst));
            }

            @Override
            public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
                super.visitLocalVariable(name, remap(desc), remap(signature), start, end, index);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return super.visitAnnotation(remap(desc), visible);
            }

        }

    }

    private class RemapBuildScriptsAction<M, T extends Script> implements Action<PersistentCache> {
        private final String classpathHash;
        private final String sourceHash;
        private final String dslId;
        private final ScriptSource source;
        private final RemappingScriptSource remapped;
        private final ClassLoader classLoader;
        private final CompileOperation<M> operation;
        private final Action<? super ClassNode> verifier;
        private final Class<T> scriptBaseClass;

        public RemapBuildScriptsAction(RemappingScriptSource remapped, String classpathHash, String sourceHash, String dslId, ClassLoader classLoader, CompileOperation<M> operation, Action<? super ClassNode> verifier, Class<T> scriptBaseClass) {
            this.classpathHash = classpathHash;
            this.sourceHash = sourceHash;
            this.dslId = dslId;
            this.remapped = remapped;
            this.source = remapped.getSource();
            this.classLoader = classLoader;
            this.operation = operation;
            this.verifier = verifier;
            this.scriptBaseClass = scriptBaseClass;
        }

        public void execute(final PersistentCache remappedClassesCache) {
            final PersistentCache cache = cacheRepository.cache(String.format("scripts/%s/%s/%s", sourceHash, dslId, classpathHash))
                .withValidator(validator)
                .withDisplayName(String.format("%s generic class cache for %s", dslId, source.getDisplayName()))
                .withInitializer(new ProgressReportingInitializer(
                    progressLoggerFactory,
                    new CompileToCrossBuildCacheAction(remapped, classLoader, operation, verifier, scriptBaseClass),
                    "Compiling script into cache",
                    "Compiling " + source.getDisplayName() + " to cross build script cache"))
                .open();
            cache.close();
            final File genericClassesDir = classesDir(cache);
            final File metadataDir = metadataDir(cache);
            remapClasses(genericClassesDir, classesDir(remappedClassesCache), remapped);
            copyMetadata(metadataDir, metadataDir(remappedClassesCache));
        }

        private void remapClasses(File scriptCacheDir, File relocalizedDir, RemappingScriptSource source) {
            ScriptSource origin = source.getSource();
            String className = origin.getClassName();
            if (!relocalizedDir.exists()) {
                relocalizedDir.mkdir();
            }
            File[] files = scriptCacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String renamed = file.getName();
                    if (renamed.startsWith(RemappingScriptSource.MAPPED_SCRIPT)) {
                        renamed = className + renamed.substring(RemappingScriptSource.MAPPED_SCRIPT.length());
                    }
                    ClassWriter cv = new ClassWriter(0);
                    BuildScriptRemapper remapper = new BuildScriptRemapper(cv, origin);
                    try {
                        ClassReader cr = new ClassReader(Files.toByteArray(file));
                        cr.accept(remapper, 0);
                        Files.write(cv.toByteArray(), new File(relocalizedDir, renamed));
                    } catch (IOException ex) {
                        throw UncheckedException.throwAsUncheckedException(ex);
                    }
                }
            }
        }

        private void copyMetadata(File source, File dest) {
            if (dest.mkdir()) {
                for (File src : source.listFiles()) {
                    try {
                        Files.copy(src, new File(dest, src.getName()));
                    } catch (IOException ex) {
                        throw UncheckedException.throwAsUncheckedException(ex);
                    }
                }
            }
        }
    }
}
