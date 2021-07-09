package com.cjbooms.fabrikt.generators.model

import com.cjbooms.fabrikt.cli.ModelCodeGenOptionType
import com.cjbooms.fabrikt.configurations.Packages
import com.cjbooms.fabrikt.generators.ClassType
import com.cjbooms.fabrikt.generators.GeneratorUtils.toClassName
import com.cjbooms.fabrikt.generators.PropertyUtils.addToClass
import com.cjbooms.fabrikt.generators.PropertyUtils.isNullable
import com.cjbooms.fabrikt.generators.TypeFactory.createList
import com.cjbooms.fabrikt.generators.TypeFactory.createMapOfMapsStringToStringAny
import com.cjbooms.fabrikt.generators.TypeFactory.createMapOfStringToType
import com.cjbooms.fabrikt.generators.TypeFactory.createMutableMapOfStringToType
import com.cjbooms.fabrikt.generators.model.JacksonMetadata.JSON_VALUE
import com.cjbooms.fabrikt.generators.model.JacksonMetadata.basePolymorphicType
import com.cjbooms.fabrikt.generators.model.JacksonMetadata.polymorphicSubTypes
import com.cjbooms.fabrikt.model.Destinations.modelsPackage
import com.cjbooms.fabrikt.model.GeneratedType
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.ModelType
import com.cjbooms.fabrikt.model.Models
import com.cjbooms.fabrikt.model.PropertyInfo
import com.cjbooms.fabrikt.model.PropertyInfo.Companion.HTTP_SETTINGS
import com.cjbooms.fabrikt.model.PropertyInfo.Companion.topLevelProperties
import com.cjbooms.fabrikt.model.SchemaInfo
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.util.KaizenParserExtensions.getSuperType
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isComplexTypedAdditionalProperties
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isEnumDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isInlinedObjectDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isOneOfPolymorphicTypes
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isPolymorphicSubType
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isPolymorphicSuperType
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isReferenceObjectDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isSimpleType
import com.cjbooms.fabrikt.util.KaizenParserExtensions.mappingKeys
import com.cjbooms.fabrikt.util.KaizenParserExtensions.safeName
import com.cjbooms.fabrikt.util.KaizenParserExtensions.toMapValueClassName
import com.cjbooms.fabrikt.util.KaizenParserExtensions.toModelClassName
import com.cjbooms.fabrikt.util.NormalisedString.toModelClassName
import com.reprezen.jsonoverlay.IJsonOverlay
import com.reprezen.jsonoverlay.Overlay
import com.reprezen.kaizen.oasparser.OpenApi3Parser
import com.reprezen.kaizen.oasparser.model3.Discriminator
import com.reprezen.kaizen.oasparser.model3.OpenApi3
import com.reprezen.kaizen.oasparser.model3.Schema
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import java.io.Serializable
import java.net.URL

class JacksonModelGenerator(
    private val packages: Packages,
    private val sourceApi: SourceApi,
    private val options: Set<ModelCodeGenOptionType> = emptySet()
) {
    companion object {
        fun toModelType(basePackage: String, typeInfo: KotlinTypeInfo, isNullable: Boolean = false): TypeName {
            val className =
                toClassName(
                    basePackage,
                    typeInfo
                )
            val typeName = when (typeInfo) {
                is KotlinTypeInfo.Array -> createList(
                    toModelType(
                        basePackage,
                        typeInfo.parameterizedType
                    )
                )
                is KotlinTypeInfo.Map ->
                    when (val paramType = typeInfo.parameterizedType) {
                        is KotlinTypeInfo.UntypedProperties -> createMapOfMapsStringToStringAny()
                        is KotlinTypeInfo.UnknownProperties ->
                            createMapOfStringToType(
                                toClassName(
                                    basePackage,
                                    paramType
                                )
                            )
                        is KotlinTypeInfo.TypedProperties ->
                            createMapOfStringToType(
                                toClassName(
                                    basePackage,
                                    paramType
                                )
                            )
                        else -> createMapOfStringToType(
                            toModelType(
                                basePackage,
                                paramType
                            )
                        )
                    }
                is KotlinTypeInfo.UntypedObject -> createMapOfStringToType(className)
                is KotlinTypeInfo.UnknownProperties -> createMutableMapOfStringToType(className)
                is KotlinTypeInfo.UntypedProperties -> createMutableMapOfStringToType(className)
                is KotlinTypeInfo.TypedProperties -> createMutableMapOfStringToType(className)
                else -> className
            }
            return if (isNullable) typeName.copy(nullable = true) else typeName
        }

        private fun toClassName(basePackage: String, typeInfo: KotlinTypeInfo): ClassName =
            if (typeInfo.modelKClass == GeneratedType::class)
                generatedType(
                    basePackage,
                    typeInfo.generatedModelClassName!!
                )
            else typeInfo.modelKClass.asTypeName()

        fun generatedType(basePackage: String, modelName: String) = ClassName(modelsPackage(basePackage), modelName)
    }

    private val externalApiSchemas = mutableMapOf<String, MutableSet<String>>()

    private fun <V> IJsonOverlay<V>.getDocumentUrl(): String? =
        Overlay.of(this).positionInfo.orElse(null)?.documentUrl

    fun generate(): Models {
        val models: MutableSet<TypeSpec> = createModels(sourceApi.openApi3, sourceApi.allSchemas)
        externalApiSchemas.forEach { externalReferences ->
            val api = OpenApi3Parser().parse(URL(externalReferences.key))
            val schemas = api.schemas.entries.map { it.key to it.value }
                .map { (key, schema) -> SchemaInfo(key, schema) }
                .filter { apiSchema -> externalReferences.value.contains(apiSchema.name) }
            val externalModels = createModels(api, schemas)
            externalModels.forEach { additionalModel ->
                if (models.none { it.name == additionalModel.name }) models.add(additionalModel)
            }
        }
        return Models(models.map { ModelType(it, packages.base) })
    }

    private fun createModels(api: OpenApi3, schemas: List<SchemaInfo>) = schemas
        .filterNot { it.schema.isSimpleType() }
        .filterNot { it.schema.isOneOfPolymorphicTypes() }
        .flatMap {
            val properties = it.schema.topLevelProperties(HTTP_SETTINGS, it.schema)
            if (properties.isNotEmpty() || it.typeInfo is KotlinTypeInfo.Enum) {
                val primaryModel = buildPrimaryModel(api, it, properties, schemas)
                val inlinedModels = buildInLinedModels(properties, it.schema, api.getDocumentUrl()!!)
                listOf(primaryModel) + inlinedModels
            } else emptyList()
        }.toMutableSet()

    private fun buildPrimaryModel(
        api: OpenApi3,
        schemaInfo: SchemaInfo,
        properties: Collection<PropertyInfo>,
        allSchemas: List<SchemaInfo>
    ): TypeSpec {
        val modelName = schemaInfo.name.toModelClassName()
        return when {
            schemaInfo.schema.isPolymorphicSuperType() -> polymorphicSuperType(
                modelName,
                properties,
                schemaInfo.schema.discriminator,
                allSchemas
            )
            schemaInfo.schema.isPolymorphicSubType(api) -> polymorphicSubType(
                modelName,
                properties,
                schemaInfo.schema.getSuperType(api)!!.let { SchemaInfo(it.name, it) }
            )
            schemaInfo.typeInfo is KotlinTypeInfo.Enum -> buildEnumClass(schemaInfo.typeInfo)
            else -> standardDataClass(modelName, properties)
        }
    }

    private fun buildInLinedModels(
        topLevelProperties: Collection<PropertyInfo>,
        enclosingSchema: Schema,
        apiDocUrl: String
    ): List<TypeSpec> =
        topLevelProperties.flatMap {
            val enclosingModelName = enclosingSchema.toModelClassName()
            if (it.schema.isInExternalDocument(apiDocUrl)) {
                it.schema.captureMissingExternalSchemas(apiDocUrl)
                emptySet()
            } else
                when (it) {
                    is PropertyInfo.ObjectInlinedField -> {
                        val props = it.schema.topLevelProperties(HTTP_SETTINGS, enclosingSchema)
                        val currentModel = standardDataClass(it.name.toModelClassName(enclosingModelName), props)
                        val inlinedModels = buildInLinedModels(props, enclosingSchema, apiDocUrl)
                        inlinedModels + currentModel
                    }
                    is PropertyInfo.ObjectRefField ->
                        when {
                            it.schema.isReferenceObjectDefinition() -> it.schema.topLevelProperties(
                                HTTP_SETTINGS, enclosingSchema
                            )
                                .let { props ->
                                    buildInLinedModels(props, enclosingSchema, apiDocUrl) +
                                        standardDataClass(it.schema.safeName().toModelClassName(), props)
                                }
                            else -> emptySet()
                        }
                    is PropertyInfo.MapField -> buildMapModel(it)?.let { mapModel -> setOf(mapModel) } ?: emptySet()
                    is PropertyInfo.AdditionalProperties ->
                        if (it.schema.isComplexTypedAdditionalProperties("additionalProperties")) setOf(
                            standardDataClass(
                                it.schema.toMapValueClassName(),
                                it.schema.topLevelProperties(HTTP_SETTINGS, enclosingSchema)
                            )
                        )
                        else emptySet()
                    is PropertyInfo.Field ->
                        if (it.typeInfo is KotlinTypeInfo.Enum) setOf(buildEnumClass(it.typeInfo as KotlinTypeInfo.Enum))
                        else emptySet()
                    is PropertyInfo.ListField ->
                        it.schema.itemsSchema.let { items ->
                            when {
                                items.isInlinedObjectDefinition() -> items.topLevelProperties(
                                    HTTP_SETTINGS, enclosingSchema
                                ).let { props ->
                                    buildInLinedModels(props, enclosingSchema, apiDocUrl) + standardDataClass(
                                        it.name.toModelClassName(enclosingModelName), props
                                    )
                                }
                                items.isReferenceObjectDefinition() -> items.topLevelProperties(
                                    HTTP_SETTINGS, enclosingSchema
                                )
                                    .let { props ->
                                        buildInLinedModels(props, enclosingSchema, apiDocUrl) +
                                            standardDataClass(items.safeName().toModelClassName(), props)
                                    }
                                items.isEnumDefinition() ->
                                    setOf(
                                        buildEnumClass(
                                            KotlinTypeInfo.from(items, "items", enclosingModelName) as KotlinTypeInfo.Enum
                                        )
                                    )
                                else -> emptySet()
                            }
                        }
                }
        }

    private fun Schema.captureMissingExternalSchemas(apiDocUrl: String, depth: Int = 0) {
        nestedSchemas().forEach { schema ->
            val docUrl = schema.getDocumentUrl()
            if (docUrl != null && docUrl != apiDocUrl) {
                externalApiSchemas.getOrPut(docUrl) { mutableSetOf() }.add(schema.safeName())
            }
            if (depth < 10) schema.captureMissingExternalSchemas(apiDocUrl, depth + 1)
        }
    }

    private fun Schema.isInExternalDocument(apiDocUrl: String): Boolean {
        val docUrl = getDocumentUrl()
        return docUrl != null && docUrl != apiDocUrl
    }

    private fun Schema.nestedSchemas() =
        (
            allOfSchemas + anyOfSchemas + oneOfSchemas + itemsSchema + additionalPropertiesSchema +
                this + this.properties.map { it.value }
            )
            .filterNotNull()
            .filter { Overlay.of(it).isPresent }

    private fun buildEnumClass(enum: KotlinTypeInfo.Enum): TypeSpec {
        val classBuilder = TypeSpec
            .enumBuilder(
                generatedType(
                    packages.base,
                    enum.enumClassName
                )
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("value", String::class)
                    .build()
            )
            .addQuarkusReflectionAnnotation()
        enum.entries.forEach {
            classBuilder.addEnumConstant(
                it.toUpperCase(),
                TypeSpec.anonymousClassBuilder()
                    .addSuperclassConstructorParameter(CodeBlock.of("\"$it\""))
                    .build()
            )
        }
        classBuilder.addProperty(
            PropertySpec.builder("value", String::class)
                .addAnnotation(JSON_VALUE)
                .initializer("value")
                .build()
        )
        return classBuilder.build()
    }

    private fun buildMapModel(mapField: PropertyInfo.MapField): TypeSpec? =
        if (mapField.schema.additionalPropertiesSchema.isComplexTypedAdditionalProperties("additionalProperties"))
            standardDataClass(
                mapField.schema.additionalPropertiesSchema.toMapValueClassName(),
                mapField.schema.additionalPropertiesSchema.topLevelProperties(HTTP_SETTINGS)
            )
        else null

    private fun standardDataClass(modelName: String, properties: Collection<PropertyInfo>): TypeSpec =
        properties.addToClass(
            TypeSpec
                .classBuilder(
                    generatedType(
                        packages.base,
                        modelName
                    )
                )
                .addModifiers(KModifier.DATA)
                .addSerializableInterface()
                .addQuarkusReflectionAnnotation(),
            ClassType.VANILLA_MODEL
        )

    private fun polymorphicSuperType(
        modelName: String,
        properties: Collection<PropertyInfo>,
        discriminator: Discriminator,
        allSchemas: List<SchemaInfo>
    ): TypeSpec {
        val classBuilder = TypeSpec.classBuilder(
            generatedType(
                packages.base,
                modelName
            )
        )
            .addModifiers(KModifier.SEALED)
        classBuilder.addAnnotation(basePolymorphicType(discriminator.propertyName))

        val subTypes = allSchemas
            .filter { model ->
                model.schema.allOfSchemas.any { allOfRef ->
                    allOfRef.name == modelName && allOfRef.discriminator == discriminator
                }
            }
        val mappings = subTypes.flatMap { schemaInfo ->
            discriminator.mappingKeys(schemaInfo.schema).map {
                it to toModelType(packages.base, KotlinTypeInfo.from(schemaInfo.schema, schemaInfo.name))
            }
        }.toMap()
        classBuilder.addAnnotation(polymorphicSubTypes(mappings)).addQuarkusReflectionAnnotation()

        return properties.addToClass(
            classBuilder,
            ClassType.SUPER_MODEL
        )
    }

    private fun polymorphicSubType(
        modelName: String,
        properties: Collection<PropertyInfo>,
        superType: SchemaInfo
    ): TypeSpec =
        properties.addToClass(
            TypeSpec
                .classBuilder(
                    generatedType(
                        packages.base,
                        modelName
                    )
                )
                .addModifiers(KModifier.DATA)
                .addSerializableInterface()
                .addQuarkusReflectionAnnotation()
                .superclass(
                    toModelType(
                        packages.base,
                        KotlinTypeInfo.from(superType.schema, superType.name)
                    )
                ),
            ClassType.SUB_MODEL
        )

    private fun Collection<PropertyInfo>.addToClass(classBuilder: TypeSpec.Builder, classType: ClassType): TypeSpec {
        val constructorBuilder = FunSpec.constructorBuilder()
        this.forEach {
            it.addToClass(
                toModelType(
                    packages.base,
                    it.typeInfo,
                    it.isNullable()
                ),
                toClassName(
                    packages.base,
                    it.typeInfo
                ),
                classBuilder,
                constructorBuilder,
                classType
            )
        }
        return classBuilder.primaryConstructor(constructorBuilder.build()).build()
    }

    private fun TypeSpec.Builder.addSerializableInterface(): TypeSpec.Builder {
        if (options.any { it == ModelCodeGenOptionType.JAVA_SERIALIZATION })
            this.addSuperinterface(Serializable::class)
        return this
    }

    private fun TypeSpec.Builder.addQuarkusReflectionAnnotation(): TypeSpec.Builder {
        if (options.any { it == ModelCodeGenOptionType.QUARKUS_REFLECTION })
            this.addAnnotation(
                AnnotationSpec.builder("RegisterForReflection".toClassName("io.quarkus.runtime.annotations")).build()
            )
        return this
    }
}
