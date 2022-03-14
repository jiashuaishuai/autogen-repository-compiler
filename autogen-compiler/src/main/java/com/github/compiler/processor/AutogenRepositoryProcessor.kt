package com.github.compiler.processor

import com.github.annotation.Autogen
import com.github.annotation.CloseScheduler
import com.github.annotation.DeprecatedApi
import com.github.compiler.utils.*
import com.squareup.kotlinpoet.*
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

@SupportedOptions("AutogenRepository")
@SupportedAnnotationTypes("com.github.annotation.Autogen")
class AutogenRepositoryProcessor : AbstractProcessor() {
    private lateinit var mFiler: Filer
    private lateinit var mElementUtils: Elements
    private lateinit var mTypeUtils: Types
    private lateinit var absRepositoryClass: ClassName
    private lateinit var apiRequestHelper: ClassName
    private lateinit var rxObservable: TypeElement
    private lateinit var baseLibBaseResponse: TypeElement
    private var mServiceName: String = ""
    private var mApiServicePackage: String = ""
    private var requestPackage: String = ""
    private var requestSimpleName: String = ""

    /**
     * 初始化常用工具
     */
    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        mTypeUtils = processingEnv.typeUtils
        mElementUtils = processingEnv.elementUtils
        mFiler = processingEnv.filer

        //获取AbsRepository和ApiRequestHelper className
        absRepositoryClass = ClassName(ABS_REPOSITORY_PACKAGE, ABS_REPOSITORY_SIMPLE_NAME)
        apiRequestHelper = ClassName(API_REQUEST_HELPER_PACKAGE, API_REQUEST_HELPER_SIMPLE_NAME)
        rxObservable = mElementUtils.getTypeElement(RX_JAVA_OBSERVABLE_CLASS_NAME)
        baseLibBaseResponse =
            mElementUtils.getTypeElement(BASE_LIB_BASE_RESPONSE_CLASS_NAME)
    }


    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latest()
    }


    override fun process(
        annotations: MutableSet<out TypeElement>?,
        roundEnv: RoundEnvironment
    ): Boolean {
        val elementsAnnotatedWith = roundEnv.getElementsAnnotatedWith(Autogen::class.java)
        elementsAnnotatedWith.forEach { element ->
            if (element.kind == ElementKind.INTERFACE) {//必须声明在接口上
                analyzeElement(element)
            }
        }
        //声明的注解不需要后续注解处理器再去处理
        return true
    }

    /**
     * 解析Element，根据解析结果生成Request和Repository
     */
    private fun analyzeElement(element: Element) {
        mServiceName = element.simpleName.toString()
        //获取当前注解修饰元素所在的包
        mApiServicePackage = mElementUtils.getPackageOf(element).qualifiedName.toString()
        generateRequestImpl(element)
        generateRepositoryImpl(element)
    }

    /**
     * 生成Request class
     */
    private fun generateRequestImpl(element: Element) {
        val host = element.getAnnotation(Autogen::class.java).host
        //生成文件所在包名以及类名
        requestPackage = AutogenUtils.getRequestPackageName(mApiServicePackage)
        requestSimpleName = AutogenUtils.getRequestClassName(mServiceName)
        //创建成员变量 ApiRequestHelper 和注解修饰的ApiService
        val apiHelper = PropertySpec
            .builder(REQUEST_HELPER_VARIABLE_NAME, apiRequestHelper)
            .addModifiers(KModifier.PRIVATE)
            .initializer("ApiRequestHelper.getInstance()")
            .build()

        val initializerString =
            if (host.isEmpty())
                "$REQUEST_HELPER_VARIABLE_NAME.createService($mServiceName::class.java)"
            else
                "$REQUEST_HELPER_VARIABLE_NAME.createService($mServiceName::class.java,\"$host\")"
        val service = PropertySpec
            .builder(SERVICE_VARIABLE_NAME, element.asType().asTypeName())
            .addModifiers(KModifier.PRIVATE)
            .initializer(initializerString)
            .build()
        //创建Request类
        FileSpec.builder(requestPackage, requestSimpleName)
            .addType(
                TypeSpec.objectBuilder(requestSimpleName)
                    .addProperty(apiHelper)
                    .addProperty(service)//添加成员变量
                    .addFunctions(generateRequestFunctions(element))//添加成员函数
                    .build()
            )
            .build().writeTo(mFiler)//写入
    }


    /**
     * 生成request 函数集合
     */
    private fun generateRequestFunctions(element: Element): ArrayList<FunSpec> {
        val requestFunctionLis = arrayListOf<FunSpec>()
        //返回包含的元素
        element.enclosedElements.forEach { subElement ->
            if (subElement.kind == ElementKind.METHOD && subElement is ExecutableElement) {
                val requestFunSpecBuilder = createFunctionBuilder(
                    subElement,
                    SERVICE_VARIABLE_NAME,
                    createDeprecatedApiAnnotation(subElement)
                )
                requestFunctionLis.add(requestFunSpecBuilder.build())
            }
        }
        return requestFunctionLis
    }

    /**
     * 生成Repository class
     */
    private fun generateRepositoryImpl(element: Element) {
        //生成文件所在包名以及类名
        val repositoryPackage = AutogenUtils.getRepositoryPackageName(mApiServicePackage)
        val repositorySimpleName = AutogenUtils.getRepositoryClassName(mServiceName)
        //创建Repository类
        FileSpec.builder(repositoryPackage, repositorySimpleName)
            .addImport(requestPackage, requestSimpleName)
            .addType(
                TypeSpec.classBuilder(repositorySimpleName)
                    .superclass(absRepositoryClass)
                    .addFunctions(generateRepositoryFunctions(element))
                    .addModifiers(KModifier.OPEN)
                    .build()
            )
            .build()
            .writeTo(mFiler)//写入
    }

    /**
     * 生成Repository 函数集合
     */

    private fun generateRepositoryFunctions(element: Element): ArrayList<FunSpec> {
        val repositoryFunctionList = arrayListOf<FunSpec>()
        //返回包含的元素
        element.enclosedElements.forEach { subElement ->
            if (subElement.kind == ElementKind.METHOD && subElement is ExecutableElement) {
                //是否关闭线程调度
                val closeScheduler = subElement.getAnnotation(CloseScheduler::class.java)
                var isApplySchedulers = false
                var isHandleResult = false
                if (closeScheduler == null) {//如果没有关闭 根据返回值类型判断使用哪个调度器
                    val returnType = subElement.returnType//获取返回值类型
                    if (returnType is DeclaredType) {
                        //检查是否以RxJava中的Observable作为返回值
                        isApplySchedulers = returnType.asElement().equals(rxObservable)
                        if (isApplySchedulers) {
                            returnType.typeArguments.forEach { observableArgument ->
                                // 检查Observable是否以baseLib中的BaseResponse作为泛型
                                isHandleResult = mTypeUtils.asElement(observableArgument)
                                    .equals(baseLibBaseResponse)
                            }
                        }
                    }
                }
                val repositoryFunSpecBuilder = createFunctionBuilder(
                    subElement,
                    requestSimpleName,
                    createDeprecatedApiAnnotation(subElement),
                    isApplySchedulers,
                    isHandleResult
                )
                repositoryFunSpecBuilder.addModifiers(KModifier.OPEN)
                repositoryFunctionList.add(repositoryFunSpecBuilder.build())
            }
        }
        return repositoryFunctionList

    }

    /**
     * 创建FunctionBuilder
     */
    private fun createFunctionBuilder(
        subElement: ExecutableElement,
        objectName: String,
        annotationSpec: AnnotationSpec? = null,
        isApplySchedulers: Boolean = false,
        isHandleResult: Boolean = false
    ): FunSpec.Builder {
        //创建函数
        val functionName = subElement.simpleName.toString()
        val functionParameters = subElement.parameters
        val funSpecBuilder = FunSpec.builder(functionName)
        //.returns(subElement.returnType.asTypeName())//返回值any会被识别为Object，导致类型不匹配
        val statementSB = StringBuilder("return $objectName.$functionName")
        if (functionParameters != null && functionParameters.size > 0) {
            statementSB.append("(")
            functionParameters.forEach { params ->
                val paramsSpec = ParameterSpec.builder(
                    params.simpleName.toString(),
                    params.asType().asTypeName()
                ).build()
                funSpecBuilder.addParameter(paramsSpec)
                statementSB.append(params.simpleName.toString())
                    .append(",")
            }
            statementSB.delete(
                statementSB.length - 1,
                statementSB.length
            )
            statementSB.append(")")
        } else {
            statementSB.append("()")
        }
        if (isApplySchedulers) {//只有在Repository类里才需要添加
            if (isHandleResult) {
                statementSB.append(".compose(handleResult())")
            } else {
                statementSB.append(".compose(applySchedulers())")
            }
        }
        funSpecBuilder.addStatement(statementSB.toString())
        if (annotationSpec != null) {
            funSpecBuilder.addAnnotation(annotationSpec)
        }
        return funSpecBuilder
    }

    /**
     *根据自定义的DeprecatedApi 创建Deprecated注解
     */
    private fun createDeprecatedApiAnnotation(subElement: Element): AnnotationSpec? {
        //解析过时注解转为kotlin过时注解
        val deprecated = subElement.getAnnotation(DeprecatedApi::class.java)
        if (deprecated != null) {
            val deprecatedMessage = deprecated.message
            val deprecatedReplaceWith = deprecated.replaceWith
            val expression = deprecatedReplaceWith.expression
            return AnnotationSpec.builder(Deprecated::class.java)
                .addMember("\"$deprecatedMessage\",ReplaceWith(\"$expression\"),DeprecationLevel.${deprecated.level}")
                .build()
        }
        return null
    }
}