package com.github.compiler.utils

object AutogenUtils {


    fun getRequestPackageName(apiServicePackageName: String) = apiServicePackageName.substring(
        0,
        apiServicePackageName.lastIndexOf(".")
    ) + DOT + REQUEST_PACKAGE_NAME

    fun getRequestClassName(apiServiceName: String) =
        "Api" + apiServiceName.replace("Service", "") + "Request"

    fun getRepositoryPackageName(apiServicePackageName: String) = apiServicePackageName.substring(
        0,
        apiServicePackageName.lastIndexOf(".")
    ) + DOT + REPOSITORY_PACKAGE_NAME

    fun getRepositoryClassName(apiServiceName: String) =
        "Abs" + apiServiceName.replace("Service", "") + "Repository"

}