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

package org.gradle.model.internal.manage.schema.extract

import org.gradle.api.Action
import org.gradle.model.internal.manage.schema.ModelSchema
import org.gradle.model.internal.manage.schema.NewStructSchema
import org.gradle.model.internal.type.ModelType
import org.gradle.platform.base.Variant
import org.gradle.platform.base.internal.NewVariantAspect
import org.gradle.platform.base.internal.VariantAspectExtractionStrategy
import spock.lang.Specification
import spock.lang.Unroll

@SuppressWarnings("GrMethodMayBeStatic")
class StructSchemaExtractionStrategyTest extends Specification {
    private static DEFAULT_ASPECT_EXTRACTOR = new ModelSchemaAspectExtractor()

    ModelSchemaExtractionContext<?> context

    @Unroll
    def "extracts no methods from #type"() {
        when:
        def extracted = extract(type)
        then:
        extracted.type == ModelType.of(type)
        extracted.properties*.name as List == []
        extracted.nonPropertyMethods*.name as List == []
        extracted.allMethods*.name as List == []
        extracted.aspects*.getClass() as List == []

        where:
        type         | _
        Object       | _
        GroovyObject | _
        Serializable | _
    }

    static abstract class TypeWithProperties {
        abstract int getX()
        abstract void setX(int value)
        abstract void setZ(int value)
        abstract int getY()
        int x() { 1 }
    }

    def "extracts simple properties from type"() {
        when: def extracted = extract(TypeWithProperties)
        then:
        extracted.type == ModelType.of(TypeWithProperties)
        extracted.properties*.name as List == ["x", "y", "z"]
        extracted.properties*.readable as List == [true, true,  false]
        extracted.properties*.writable as List == [true, false, true]
        extracted.nonPropertyMethods*.name as List == ["x"]
        extracted.allMethods*.name as List == ["getX", "getY", "setX", "setZ", "x"]
        extracted.aspects*.getClass() as List == []
    }

    static abstract class ChildTypeWithProperties extends TypeWithProperties {
        abstract int getA()
        void setA(int value) {}

        @Override
        abstract int getY()

        // This makes "z" readable
        abstract int getZ()
    }

    def "extracts simple properties from derived type"() {
        when: def extracted = extract(ChildTypeWithProperties)
        then:
        extracted.type == ModelType.of(ChildTypeWithProperties)
        extracted.properties*.name as List == ["a", "x", "y", "z"]
        extracted.properties*.readable as List == [true, true, true,  true]
        extracted.properties*.writable as List == [true, true, false, true]
        extracted.properties*.declaredBy as List == [
            [ModelType.of(ChildTypeWithProperties)] as Set,
            [ModelType.of(TypeWithProperties)] as Set,
            [ModelType.of(ChildTypeWithProperties), ModelType.of(TypeWithProperties)] as Set,
            [ModelType.of(ChildTypeWithProperties), ModelType.of(TypeWithProperties)] as Set
        ]
        extracted.nonPropertyMethods*.name as List == ["x"]
        extracted.allMethods*.name as List == ["getA", "getX", "getY", "getZ", "setA", "setX", "setZ", "x"]
    }

    abstract class TypeWithBooleanProperties {
        abstract boolean isInvalidIsGetter(boolean value)
        abstract boolean getInvalidIsGetter()
        abstract void setInvalidIsGetter(boolean value)

        abstract boolean isIsAndGetReadOnly()
        abstract boolean getIsAndGetReadOnly()

        abstract boolean isIsAndGetReadWrite()
        abstract boolean getIsAndGetReadWrite()
        abstract void setIsAndGetReadWrite(boolean value)

        abstract boolean isMixedType()
        abstract int getMixedType()
        abstract void setMixedType(boolean value)

        abstract boolean isInvalidMixedType()
        abstract int getInvalidMixedType()
        abstract void setInvalidMixedType(int value)

        abstract boolean isNotProperty(boolean value)

        abstract boolean isOnlyIsReadOnly()

        abstract boolean isOnlyIsReadWrite()
        abstract void setOnlyIsReadWrite(boolean value)
    }

    def "handles conflicting is getters"() {
        when: def extracted = extract(TypeWithBooleanProperties)
        then:
        extracted.type == ModelType.of(TypeWithBooleanProperties)
        extracted.properties*.name as List == ["invalidIsGetter", "invalidMixedType", "isAndGetReadOnly", "isAndGetReadWrite", "mixedType", "onlyIsReadOnly", "onlyIsReadWrite"]
        extracted.properties*.readable as List == [true, true, true, true, true, true,  true]
        extracted.properties*.writable as List == [true, true, false, true, true, false, true]
        extracted.nonPropertyMethods*.name as List == [
            "getIsAndGetReadOnly",
            "getIsAndGetReadWrite",
            "getMixedType",
            "isInvalidIsGetter",
            "isInvalidMixedType",
            "isNotProperty"
        ]
        extracted.allMethods*.name as List == [
            "getInvalidIsGetter",
            "getInvalidMixedType",
            "getIsAndGetReadOnly",
            "getIsAndGetReadWrite",
            "getMixedType",
            "isInvalidIsGetter",
            "isInvalidMixedType",
            "isIsAndGetReadOnly",
            "isIsAndGetReadWrite",
            "isMixedType",
            "isNotProperty",
            "isOnlyIsReadOnly",
            "isOnlyIsReadWrite",
            "setInvalidIsGetter",
            "setInvalidMixedType",
            "setIsAndGetReadWrite",
            "setMixedType",
            "setOnlyIsReadWrite",
        ]
    }

    static abstract class TypeWithPropertyVariants {
        @Variant
        abstract String getX()
        abstract void setX(String value)

        @Variant
        // Only String and Named typed properties can serve as variant
        abstract boolean isY()

        @Variant
        // Ignored because only getters can declare aspects
        abstract void setZ(int value)

        @Variant
        // Ignored as it is not a property
        int x() { 1 }
    }

    def "extracts simple property with aspect"() {
        when: def extracted = extract(TypeWithPropertyVariants, new VariantAspectExtractionStrategy())
        then:
        extracted.type == ModelType.of(TypeWithPropertyVariants)
        extracted.properties*.name as List == ["x", "y", "z"]
        extracted.nonPropertyMethods*.name as List == ["x"]
        extracted.aspects*.getClass() as List == [NewVariantAspect]
        (extracted.aspects[0] as NewVariantAspect).dimensions*.name as List == ["x"]
    }

    NewStructSchema extract(Class type, ModelSchemaAspectExtractionStrategy... aspectStrategies) {
        context = extractionContext(type)
        new NewStructSchemaExtractionStrategy(new ModelSchemaAspectExtractor(aspectStrategies as List)).extract(context)
        assert context.result instanceof NewStructSchema
        return (NewStructSchema) context.result
    }

    private DefaultModelSchemaExtractionContext extractionContext(Class type, String description = "test", Action<? super ModelSchema> validator = {}) {
        return new DefaultModelSchemaExtractionContext(null, ModelType.of(type), description, validator)
    }
}
