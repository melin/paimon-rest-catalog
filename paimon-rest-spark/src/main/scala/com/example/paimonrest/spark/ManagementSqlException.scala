package com.example.paimonrest.spark

/**
 * 管理语句在解析阶段就被判定为非法时抛出。
 *
 * <p>与「这条 SQL 不是管理语句」区分开：后者不是错误，只是本扩展不负责，
 * 会原样交回 Spark 的原生解析器；而本异常表示调用方写的确实是管理语句、
 * 但形状不对（例如对象级授权缺少命名空间），需要直接告知用户。
 */
class ManagementSqlException(message: String) extends RuntimeException(message)
