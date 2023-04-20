package ru.sejapoe.routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.kspDependencies
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
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


        val provided = providedParams.map { ksValueParameter ->
            ProvidedParam(
                ksValueParameter.name!!.asString(),
                ksValueParameter.type,
                func.parameters.indexOf(ksValueParameter)
            )
        }


        val afterAuthRemained = afterParamsRemained - providedParams.toSet()

        val bodyParams =
            afterAuthRemained.filter {
                !it.annotations.any() || it.annotations.any { ann -> ann.shortName.asString() == Body::class.simpleName }
            }
        if (bodyParams.size > 1) {
            throw IllegalArgumentException("Only one body parameter is allowed")
        }

        val body = bodyParams.map { ksValueParameter ->
            BodyParam(
                ksValueParameter.name!!.asString(),
                ksValueParameter.type,
                func.parameters.indexOf(ksValueParameter)
            )
        }.firstOrNull()

        val returnType = func.returnType ?: throw IllegalArgumentException("Return type is required")
        val resolvedReturnType = returnType.resolve()
        val returnTypeDeclaration = resolvedReturnType.declaration
        if (returnTypeDeclaration !is KSClassDeclaration || returnTypeDeclaration.qualifiedName?.asString() != Response::class.java.name && returnTypeDeclaration.qualifiedName?.asString() != "kotlin.Unit") {
            throw IllegalArgumentException("Return type should be MyResponse<*> or Unit")
        }


        return RouteInfo(
            HttpMethod(httpMethod), // TODO
            fullPath,
            className,
            func.simpleName.asString(),
            params,
            body,
            provided,
            resolvedReturnType
        )
    }

    private fun parseParams(
        pathParamsDecl: List<String>,
        parameters: List<KSValueParameter>
    ): Pair<List<PathParam>, List<KSValueParameter>> {
        val namedParameters = parameters.associateBy {
            val pathAnnotation =
                it.annotations.firstOrNull { ann -> ann.shortName.asString() == Path::class.simpleName }
            pathAnnotation?.arguments?.first()?.value?.toString() ?: it.name!!.asString()
        }

        val pathParamsDeclSet = pathParamsDecl.map { it.removeSuffix("?") }.toSet()
        if (!namedParameters.keys.containsAll(pathParamsDeclSet)) {
            throw IllegalArgumentException("Path parameters in function and route are not equal")
        }

        val remainingParameters =
            parameters.toSet() - namedParameters.filterKeys { it in pathParamsDeclSet }.values.toSet()

        val pathParams = pathParamsDecl.map {
            val parameter = namedParameters[it.removeSuffix("?")]!!
            val converterClass =
                parameter.annotations.firstOrNull { ann -> ann.shortName.asString() == Converter::class.simpleName }
                    ?.arguments?.first()?.value as? KSType

            PathParam(
                it.removeSuffix("?"),
                parameter.type,
                parameters.indexOf(parameter),
                converterClass,
                !it.endsWith("?")
            )
        }

        return pathParams to remainingParameters.toList()
    }

    override fun finish() {
        val objects = classes.map { createRouter(it) }
        val builder = FileSpec.builder("", "RouterImpl")
            .addImport("io.ktor.server.routing", "routing")
        val objBuilder = TypeSpec.Companion.objectBuilder("RouterImpl")
            .addSuperinterface(Router::class)
        val funBuilder = FunSpec.builder("registerRoutes")
            .receiver(Application::class)
            .addParameter(
                "providers",
                ProviderRegistry::class
            )
            .addModifiers(KModifier.OVERRIDE)
        funBuilder.beginControlFlow("routing")
        objects.forEach { obj ->
            val member = MemberName("", obj)
            funBuilder.addStatement("%M(providers)", member)
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
            .addParameter(
                "providers",
                ProviderRegistry::class
            )
            .receiver(Routing::class)
        routes.forEach { routeInfo ->
            funBuilder.beginControlFlow(
                "%M(%S)",
                methodFunctions[routeInfo.httpMethod] ?: throw IllegalArgumentException("Unsupported method"),
                routeInfo.path
            )

            routeInfo.provided.forEach {
                val type = it.type.resolve()
                funBuilder.addStatement(
                    "val ${it.name}Provider = providers.get<%T>() ?: throw IllegalArgumentException(%S)",
                    type.toTypeName(),
                    "No provider found for ${type.toTypeName()}"
                )
                funBuilder.addStatement(
                    "val ${it.name} = ${it.name}Provider.provide(%M)",
                    callFunction,
                )
            }

            routeInfo.params.forEach { param ->
                val ksType = param.type.resolve()
                if (ksType.isMarkedNullable && param.isRequired) throw IllegalArgumentException("Parameter is nullable but required")
                if (!ksType.isMarkedNullable && !param.isRequired) throw IllegalArgumentException("Parameter (${ksType}) is not nullable but not required")
                if (param.converterClass != null) {
                    if ((param.converterClass.declaration as KSClassDeclaration).superTypes.first()
                            .resolve().arguments.first().type?.resolve() != ksType
                    ) throw IllegalArgumentException("Converter and parameter type are mismatched")
                    val requirer =
                        if (param.isRequired) "?: throw IllegalArgumentException(\"${param.name} is required\")" else ""
                    funBuilder.addStatement(
                        "val ${param.name} = %M.parameters[%S]?.let { %T.%M(it) } $requirer",
                        callFunction,
                        param.name,
                        param.converterClass.toTypeName(),
                        MemberName("", "fromString")
                    )
                } else {
                    val requirer =
                        if (param.isRequired) "\n?: throw IllegalArgumentException(\"${param.name} should be convertable to ${ksType.declaration.qualifiedName?.asString()}\")" else ""
                    funBuilder.addStatement(
                        "val ${param.name} = %M.parameters[%S]?.%M()$requirer",
                        callFunction,
                        param.name,
                        primitiveConverters[ksType.declaration.qualifiedName?.asString()]
                            ?: throw IllegalArgumentException(
                                "Unsupported type"
                            )
                    )
                }
            }
            if (routeInfo.body != null) {
                val type = routeInfo.body.type.resolve()
                val args =
                    if (type.arguments.isNotEmpty())
                        type.toClassName()
                            .parameterizedBy(type.arguments.mapNotNull { it.type?.resolve()?.toTypeName() })
                    else type.toTypeName()
                funBuilder.addStatement(
                    "val ${routeInfo.body.name} = %M.%M<%T>()",
                    callFunction,
                    receiveFunction,
                    args
                )
            }

            val params = routeInfo.params.asSequence().map { it.position to it.name }.plus(
                listOfNotNull(routeInfo.body?.let { it.position to it.name })
            ).plus(
                routeInfo.provided.map { it.position to it.name }
            ).sortedBy { it.first }.map { it.second }.joinToString { it }

            if (routeInfo.returnType.declaration.qualifiedName?.asString() == "kotlin.Unit") {
                funBuilder.addStatement("${routeInfo.className}.${routeInfo.functionName}($params)")
                funBuilder.addStatement("%M.%M(%M.OK)", callFunction, respondFunction, statusObject)
            } else {
                funBuilder.addStatement("val result = ${routeInfo.className}.${routeInfo.functionName}($params)")
                funBuilder.beginControlFlow("if (result.isSuccessful)")
                funBuilder.addStatement("%M.%M(result.status, result.data!!)", callFunction, respondFunction)
                funBuilder.endControlFlow()
                    .beginControlFlow("else")
                funBuilder.addStatement("%M.%M(result.status)", callFunction, respondFunction)
                funBuilder.endControlFlow()
            }
            funBuilder.endControlFlow()
        }
        builder.addFunction(funBuilder.build())

        val fileSpec = builder.build()

        val dependencies = fileSpec.kspDependencies(aggregating = false)
        fileSpec.writeTo(codeGenerator, dependencies)
        return fileName.toCamelCase()
    }

    private class PathParam(
        val name: String,
        val type: KSTypeReference,
        val position: Int,
        val converterClass: KSType? = null,
        val isRequired: Boolean = true
    )

    private class BodyParam(val name: String, val type: KSTypeReference, val position: Int) // TODO : required
    private class ProvidedParam(val name: String, val type: KSTypeReference, val position: Int)
    private class RouteInfo(
        val httpMethod: HttpMethod,
        val path: String,
        val className: String,
        val functionName: String,
        val params: List<PathParam>,
        val body: BodyParam?,
        val provided: List<ProvidedParam>,
        val returnType: KSType
    )

    private class RouterInfo(val className: String, val routes: List<RouteInfo>)

    companion object {
        private val methodAnnotations = listOf(
            Get::class.simpleName,
            Post::class.simpleName,
            Put::class.simpleName,
            Delete::class.simpleName,
            Patch::class.simpleName,
            Head::class.simpleName,
            Options::class.simpleName
        )
        private val primitiveConverters = mapOf(
            "kotlin.String" to MemberName("kotlin.text", "toString"),
            "kotlin.Int" to MemberName("kotlin.text", "toIntOrNull"),
            "kotlin.Long" to MemberName("kotlin.text", "toLongOrNull"),
            "kotlin.Double" to MemberName("kotlin.text", "toDoubleOrNull"),
            "kotlin.Float" to MemberName("kotlin.text", "toFloatOrNull"),
            "kotlin.Boolean" to MemberName("kotlin.text", "toBoolean"),
            "kotlin.Byte" to MemberName("kotlin.text", "toByteOrNull"),
            "kotlin.Short" to MemberName("kotlin.text", "toShortOrNull"),
            "kotlin.Char" to MemberName("kotlin.text", "firstOrNull"),
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
    }
}

private fun String.toCamelCase(): String {
    return replaceFirstChar { it.lowercase(Locale.getDefault()) }
}
