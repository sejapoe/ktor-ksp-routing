package ru.sejapoe.routing

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.kspDependencies
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import java.util.*

class RouteProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return RouteProcessor(environment.codeGenerator, environment.options)
    }
}

class RouteProcessor(val codeGenerator: CodeGenerator, val options: Map<String, String>) : SymbolProcessor {
    private val classes = mutableListOf<RouterInfo>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotatedClasses = resolver.getSymbolsWithAnnotation(Route::class.java.name)
        classes.addAll(
            annotatedClasses.filterIsInstance<KSClassDeclaration>()
                .filter { it.parentDeclaration == null }
                .associateWith(::processClass)
                .map { RouterInfo(it.key.simpleName.asString(), it.value) })
        return emptyList()
    }

    private fun processClass(
        ksAnnotated: KSClassDeclaration,
        parentPath: String = ""
    ): List<RouteInfo> {
        if (ksAnnotated.classKind != ClassKind.OBJECT) throw IllegalArgumentException("@Route allowed only for objects")
        val routeAnnotation = ksAnnotated.annotations.first { it.shortName.asString() == Route::class.simpleName }
        val path = routeAnnotation.arguments.first { it.name?.asString() == "path" }.value?.toString() ?: ""
        val fullPath = "$parentPath$path"
        val routes = ksAnnotated.declarations.filterIsInstance<KSFunctionDeclaration>()
            .filter { func -> func.annotations.any { ann -> methodAnnotations.contains(ann.shortName.asString()) } }
            .map { processFunction(it, fullPath, ksAnnotated.qualifiedName!!.asString()) }.toMutableList()
        routes.addAll(
            ksAnnotated.declarations.filterIsInstance<KSClassDeclaration>()
                .filter { it.annotations.any { ann -> ann.shortName.asString() == Route::class.simpleName } }
                .flatMap { processClass(it, fullPath) })
        return routes
    }

    private fun processFunction(func: KSFunctionDeclaration, parentPath: String, className: String): RouteInfo {
        val functionAnnotation = func.annotations.first { ann -> methodAnnotations.contains(ann.shortName.asString()) }
        val httpMethod = functionAnnotation.shortName.asString().uppercase()
        val path =
            functionAnnotation.arguments.first { arg -> arg.name?.asString() == "path" }.value?.toString()

        val fullPath = "$parentPath$path"

        val pathParamsDecl =
            fullPath.split("/").filter { it.startsWith("{") && it.endsWith("}") }.map { it.substring(1, it.length - 1) }

        val (params, afterParamsRemained) = parseParams(pathParamsDecl, func.parameters)

        val providedParams =
            func.parameters.filter { it.annotations.any { ann -> ann.shortName.asString() == Provided::class.simpleName } }


        params += providedParams.map { ksValueParameter ->
            Param(
                ksValueParameter.name!!.asString(),
                ksValueParameter.type,
                func.parameters.indexOf(ksValueParameter),
                ParamType.PROVIDED
            )
        }

        val pipelineParams =
            func.parameters.filter { it.annotations.any{ ann -> ann.shortName.asString() == Pipeline::class.simpleName } }

        if (pipelineParams.size > 1) throw IllegalArgumentException("Only one pipeline parameter is allowed")

        params += pipelineParams.map { ksValueParameter ->
            val ksType = ksValueParameter.type.resolve()
            if (ksType.toClassName().simpleName != PipelineContext::class.simpleName
                || ksType.arguments.mapNotNull { it.type?.resolve()?.toClassName()?.simpleName } != listOf(Unit::class.simpleName, ApplicationCall::class.simpleName)
            ) throw IllegalArgumentException("Pipeline parameter should be PipelineContext<Unit, ApplicationCall> type")
            Param(
                ksValueParameter.name!!.asString(),
                ksValueParameter.type,
                func.parameters.indexOf(ksValueParameter),
                ParamType.PIPELINE
            )
        }

        val afterAuthRemained = afterParamsRemained - providedParams.toSet() - pipelineParams.toSet()

        val bodyParams =
            afterAuthRemained.filter {
                !it.annotations.any() || it.annotations.any { ann -> ann.shortName.asString() == Body::class.simpleName }
            }
        if (bodyParams.size > 1) {
            throw IllegalArgumentException("Only one body parameter is allowed")
        }

        params += bodyParams.map { ksValueParameter ->
            Param(
                ksValueParameter.name!!.asString(),
                ksValueParameter.type,
                func.parameters.indexOf(ksValueParameter),
                ParamType.BODY
            )
        }

        val returnType = func.returnType ?: throw IllegalArgumentException("Return type is required")
        val resolvedReturnType = returnType.resolve()
        val returnTypeDeclaration = resolvedReturnType.declaration


        return RouteInfo(
            HttpMethod(httpMethod),
            fullPath,
            className,
            func.simpleName.asString(),
            params,
            resolvedReturnType
        )
    }

    private fun parseParams(
        pathParamsDecl: List<String>,
        parameterList: List<KSValueParameter>
    ): Pair<MutableList<Param>, MutableList<KSValueParameter>> {
        val parameters = parameterList.toMutableSet()
        val namedParameters = parameters.associateBy {
            val pathAnnotation =
                it.annotations.firstOrNull { ann -> ann.shortName.asString() == Path::class.simpleName }
            pathAnnotation?.arguments?.first()?.value?.toString() ?: it.name!!.asString()
        }

        val pathParamsDeclSet = pathParamsDecl.map { it.removeSuffix("?") }.toSet()
        if (!namedParameters.keys.containsAll(pathParamsDeclSet)) {
            throw IllegalArgumentException("Path parameters in function and route are not equal")
        }

        parameters -= namedParameters.filterKeys { it in pathParamsDeclSet }.values.toSet()

        val parsedParams = mutableListOf<Param>()
        parsedParams += pathParamsDecl.map {
            val parameter = namedParameters[it.removeSuffix("?")]!!
            val converterClass =
                parameter.annotations.firstOrNull { ann -> ann.shortName.asString() == Convert::class.simpleName }
                    ?.arguments?.first()?.value as? KSType

            StringParam(
                it.removeSuffix("?"),
                parameter.type,
                parameters.indexOf(parameter),
                ParamType.PATH,
                !it.endsWith("?"),
                converterClass
            )
        }


        parsedParams += parameters.mapNotNull {
            val annotations =
                it.annotations.filter { ann -> paramAnnotations.contains(ann.shortName.asString()) }.toList()
            if (annotations.size > 1) {
                throw IllegalArgumentException("Only one annotation is allowed for parameter")
            }
            if (annotations.isEmpty()) return@mapNotNull null
            val annotation = annotations.first()
            val name = annotation.arguments.firstOrNull()?.value?.toString()?.ifEmpty { null } ?: it.name!!.asString()
            val converterClass =
                it.annotations.firstOrNull { ann -> ann.shortName.asString() == Convert::class.simpleName }
                    ?.arguments?.first()?.value as? KSType
            val paramType = when (annotation.shortName.asString()) {
                Query::class.simpleName -> ParamType.QUERY
                Header::class.simpleName -> ParamType.HEADER
                else -> throw IllegalArgumentException("Unknown annotation")
            }
            StringParam(name, it.type, parameters.indexOf(it), paramType, null, converterClass)
        }

        return parsedParams to parameters.toMutableList()
    }

    override fun finish() {
        val objects = classes.map { createRouter(it) }
        val builder = FileSpec.builder("", "RouterImpl")
            .addImport("io.ktor.server.routing", "routing")
        val objBuilder = TypeSpec.Companion.objectBuilder("RouterImpl")
            .addSuperinterface(Router::class)
        val funBuilder = FunSpec.builder("registerRoutes")
            .receiver(Application::class)
            .addParameter("providers", ProviderRegistry::class)
            .addParameter("converters", ConverterRegistry::class)
            .addModifiers(KModifier.OVERRIDE)
        funBuilder.beginControlFlow("routing")
        objects.forEach { obj ->
            val member = MemberName("", obj)
            funBuilder.addStatement("%M(providers, converters)", member)
        }
        funBuilder.endControlFlow()
        objBuilder.addFunction(funBuilder.build())
        builder.addType(objBuilder.build())

        val fileSpec = builder.build()

        val dependencies = fileSpec.kspDependencies(aggregating = false)

        fileSpec.writeTo(codeGenerator, dependencies)
    }

    private fun createRouter(routerInfo: RouterInfo): String {
        val fileName = routerInfo.className + "Shadow"
        val routes = routerInfo.routes
        val builder = FileSpec.builder("", fileName)

        val funBuilder = FunSpec.builder(fileName.toCamelCase())
            .addParameter("providers", ProviderRegistry::class)
            .addParameter("converters", ConverterRegistry::class)
            .receiver(Routing::class)
        routes.forEach { routeInfo ->
            funBuilder.beginControlFlow(
                "%M(%S)",
                methodFunctions[routeInfo.httpMethod] ?: throw IllegalArgumentException("Unsupported method"),
                routeInfo.path
            )

            routeInfo.params.filter { it.paramType === ParamType.PROVIDED }.forEach {
                val type = it.type.resolve()
                funBuilder.addStatement(
                    "val ${it.name}Provider = providers.get<%T>() ?: throw %M(%S)",
                    type.toTypeName(),
                    badRequestException,
                    "No provider found for ${type.toTypeName()}"
                )
                funBuilder.addStatement(
                    "val ${it.name} = ${it.name}Provider.provide(%M)",
                    callFunction,
                )
            }


            routeInfo.params.filterIsInstance<StringParam>().forEach { param ->
                val ksType = param.type.resolve()
                val isRequired = param.isRequired ?: !ksType.isMarkedNullable
                if (ksType.isMarkedNullable && isRequired) throw IllegalArgumentException("Parameter (${ksType}/${routeInfo.path}) is nullable but required")
                if (!ksType.isMarkedNullable && !isRequired) throw IllegalArgumentException("Parameter (${ksType}) is not nullable but not required")
                if (param.converterClass != null) {
                    if ((param.converterClass.declaration as KSClassDeclaration).superTypes.first()
                            .resolve().arguments.first().type?.resolve() != ksType
                    ) throw IllegalArgumentException("Converter and parameter type are mismatched")
                    val requirer =
                        if (isRequired) "\n?: throw BadRequestException(\"${param.name} is required\")" else ""
                    funBuilder.addStatement(
                        "val ${param.name} = %M.${param.paramType.container}[%S]?.let { %T.%M(it) } $requirer",
                        callFunction,
                        param.name,
                        param.converterClass.toTypeName(),
                        MemberName("", "fromString")
                    )
                } else {
                    val requirer =
                        if (isRequired) "\n?: throw BadRequestException(\"${param.name} should be convertable to ${ksType.declaration.qualifiedName?.asString()}\")" else ""
                    funBuilder.addStatement(
                        "val ${param.name}Converter = converters.get<%T>() ?: throw %M(%S)",
                        ksType.toTypeName().copy(nullable = false),
                        badRequestException,
                        "No converter found for ${ksType.toTypeName()}, specify it with @Converter annotation or register it in the plugin configuration"
                    )
                    funBuilder.addStatement(
                        "val ${param.name} = %M.${param.paramType.container}[%S]?.let { ${param.name}Converter.fromString(it) }$requirer",
                        callFunction,
                        param.name
                    )
                }
            }

            val pipeline = routeInfo.params.firstOrNull { it.paramType === ParamType.PIPELINE }
            if (pipeline != null) {
                funBuilder.addStatement("val ${pipeline.name} = this")
            }

            val body = routeInfo.params.firstOrNull { it.paramType === ParamType.BODY }
            if (body != null) {
                val type = body.type.resolve()
                val args =
                    if (type.arguments.isNotEmpty())
                        type.toClassName()
                            .parameterizedBy(type.arguments.mapNotNull { it.type?.resolve()?.toTypeName() })
                    else type.toTypeName()
                funBuilder.addStatement(
                    "val ${body.name} = %M.%M<%T>()",
                    callFunction,
                    receiveFunction,
                    args
                )
            }

            val params = routeInfo.params.asSequence()
                .map { it.position to it.name }
                .sortedBy { it.first }
                .map { it.second }
                .joinToString { it }

            if (routeInfo.returnType.declaration.qualifiedName?.asString() == "kotlin.Unit") {
                funBuilder.addStatement("${routeInfo.className}.${routeInfo.functionName}($params)")
                funBuilder.addStatement("%M.%M(%M.OK)", callFunction, respondFunction, statusObject)
            } else {
                funBuilder.addStatement("val result = ${routeInfo.className}.${routeInfo.functionName}($params)")
                funBuilder.addStatement("%M.%M(result)", callFunction, respondFunction)
            }
            funBuilder.endControlFlow()
        }
        builder.addFunction(funBuilder.build())

        val fileSpec = builder.build()

        val dependencies = fileSpec.kspDependencies(aggregating = false)
        fileSpec.writeTo(codeGenerator, dependencies)
        return fileName.toCamelCase()
    }

    enum class ParamType(val container: String = "") {
        PATH("parameters"), BODY, PROVIDED, QUERY("request.queryParameters"), HEADER("request.headers"), PIPELINE
    }

    private open class Param(
        val name: String,
        val type: KSTypeReference,
        val position: Int,
        val paramType: ParamType,
        val isRequired: Boolean? = null
    )

    private class StringParam(
        name: String,
        type: KSTypeReference,
        position: Int,
        paramType: ParamType,
        isRequired: Boolean? = null,
        val converterClass: KSType? = null
    ) : Param(name, type, position, paramType, isRequired)

    private class RouteInfo(
        val httpMethod: HttpMethod,
        val path: String,
        val className: String,
        val functionName: String,
        val params: List<Param>,
        val returnType: KSType
    )

    private class RouterInfo(val className: String, val routes: List<RouteInfo>)

    companion object {
        private val paramAnnotations = listOf(
            Query::class.simpleName,
            Header::class.simpleName,
        )
        private val methodAnnotations = listOf(
            Get::class.simpleName,
            Post::class.simpleName,
            Put::class.simpleName,
            Delete::class.simpleName,
            Patch::class.simpleName,
            Head::class.simpleName,
            Options::class.simpleName
        )
        private val methodFunctions = mapOf(
            HttpMethod.Get to MemberName("io.ktor.server.routing", "get"),
            HttpMethod.Post to MemberName("io.ktor.server.routing", "post"),
            HttpMethod.Put to MemberName("io.ktor.server.routing", "put"),
            HttpMethod.Delete to MemberName("io.ktor.server.routing", "delete"),
            HttpMethod.Patch to MemberName("io.ktor.server.routing", "patch"),
            HttpMethod.Head to MemberName("io.ktor.server.routing", "head"),
            HttpMethod.Options to MemberName("io.ktor.server.routing", "options"),
        )
        private val callFunction = MemberName("io.ktor.server.application", "call")
        private val respondFunction = MemberName("io.ktor.server.response", "respond")
        private val receiveFunction = MemberName("io.ktor.server.request", "receive")
        private val statusObject = MemberName("io.ktor.http", "HttpStatusCode")
        private val badRequestException = MemberName("io.ktor.server.plugins", "BadRequestException")
    }
}

private fun String.toCamelCase(): String {
    return replaceFirstChar { it.lowercase(Locale.getDefault()) }
}
