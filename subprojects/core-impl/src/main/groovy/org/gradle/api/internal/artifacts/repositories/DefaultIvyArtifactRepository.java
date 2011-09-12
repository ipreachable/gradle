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
package org.gradle.api.internal.artifacts.repositories;

import org.apache.ivy.plugins.resolver.DependencyResolver;
import org.apache.ivy.plugins.resolver.FileSystemResolver;
import org.apache.ivy.plugins.resolver.RepositoryResolver;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.repositories.IvyArtifactRepository;
import org.gradle.api.internal.artifacts.ivyservice.LocalFileRepositoryCacheManager;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.util.WrapUtil;
import org.jfrog.wharf.ivy.resolver.UrlWharfResolver;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DefaultIvyArtifactRepository implements IvyArtifactRepository, ArtifactRepositoryInternal {
    private String name;
    private String username;
    private String password;
    private final Set<String> artifactPatterns = new LinkedHashSet<String>();
    private final Set<String> ivyPatterns = new LinkedHashSet<String>();
    private final FileResolver resolver;

    public DefaultIvyArtifactRepository(FileResolver resolver) {
        this.resolver = resolver;
    }

    public void createResolvers(Collection<DependencyResolver> resolvers) {
        if (artifactPatterns.isEmpty()) {
            throw new InvalidUserDataException("You must specify at least one artifact pattern for an Ivy repository.");
        }
        List<ResolvedPattern> resolvedArtifactPatterns = resolvePatterns(artifactPatterns);
        List<ResolvedPattern> resolvedIvyPatterns = ivyPatterns.isEmpty() ? resolvedArtifactPatterns : resolvePatterns(ivyPatterns);
        Set<String> schemes = getUniqueSchemes(resolvedArtifactPatterns, resolvedIvyPatterns);

        RepositoryResolver resolver = createResolver(schemes);
        resolver.setName(name);

        for (ResolvedPattern resolvedPattern : resolvedArtifactPatterns) {
            resolver.addArtifactPattern(resolvedPattern.absolutePattern);
        }
        for (ResolvedPattern resolvedIvyPattern : resolvedIvyPatterns) {
            resolver.addIvyPattern(resolvedIvyPattern.absolutePattern);
        }
        resolvers.add(resolver);
    }

    private List<ResolvedPattern> resolvePatterns(Set<String> artifactPatterns) {
        List<ResolvedPattern> resolvedPatterns = new ArrayList<ResolvedPattern>();
        for (String artifactPattern : artifactPatterns) {
            ResolvedPattern pattern = new ResolvedPattern(artifactPattern);
            resolvedPatterns.add(pattern);
        }
        return resolvedPatterns;
    }

    private RepositoryResolver createResolver(Set<String> schemes) {
        if (WrapUtil.toSet("http", "https").containsAll(schemes)) {
            return http();
        }
        if (WrapUtil.toSet("file").containsAll(schemes)) {
            return file();
        }
        return url();
    }

    private Set<String> getUniqueSchemes(List<ResolvedPattern> patterns, List<ResolvedPattern> ivyPatterns) {
        Set<String> schemes = new LinkedHashSet<String>();
        for (ResolvedPattern pattern : patterns) {
            schemes.add(pattern.scheme);
        }
        for (ResolvedPattern pattern : ivyPatterns) {
            schemes.add(pattern.scheme);
        }
        return schemes;
    }

    private RepositoryResolver url() {
        return new UrlWharfResolver();
    }

    private RepositoryResolver file() {
        FileSystemResolver resolver = new FileSystemResolver();
        resolver.setRepositoryCacheManager(new LocalFileRepositoryCacheManager(name));
        return resolver;
    }

    private RepositoryResolver http() {
        RepositoryResolver resolver = new RepositoryResolver();
        resolver.setRepository(new CommonsHttpClientBackedRepository(username, password));
        return resolver;
    }

    public void artifactPattern(String pattern) {
        artifactPatterns.add(pattern);
    }

    public void ivyPattern(String pattern) {
        ivyPatterns.add(pattern);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUserName() {
        return username;
    }

    public void setUserName(String username) {
        this.username = username;
    }

    private class ResolvedPattern {
        public final String scheme;
        public final String absolutePattern;

        public ResolvedPattern(String rawPattern) {
            // get rid of the ivy [] token, as [ ] are not valid URI characters
            int pos = rawPattern.indexOf('[');
            String basePath = pos < 0 ? rawPattern : rawPattern.substring(0, pos);
            URI baseUri = resolver.resolveUri(basePath);
            scheme = baseUri.getScheme().toLowerCase();
            String pattern = pos < 0 ? "" : rawPattern.substring(pos);
            if ("file".equalsIgnoreCase(scheme)) {
                absolutePattern = baseUri.getPath() + '/' + pattern;
            } else {
                absolutePattern = baseUri.toString() + pattern;
            }
        }
    }
}
