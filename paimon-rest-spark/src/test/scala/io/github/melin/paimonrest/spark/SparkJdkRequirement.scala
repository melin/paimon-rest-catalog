package io.github.melin.paimonrest.spark

import org.apache.hadoop.security.UserGroupInformation

/**
 * 建 SparkSession 之前对构建用 JDK 的前置检查。
 *
 * <p><b>为什么需要这个检查。</b>Spark 建会话时会先取当前用户名，走的是 Hadoop 的
 * `UserGroupInformation.getCurrentUser()`，而它内部调用 `Subject.getSubject(...)`。
 * 这个 API 在 JDK 24 起被永久移除（JEP 486 永久关闭 Security Manager），调用直接抛
 * `UnsupportedOperationException: getSubject is not supported`，且**没有任何 JVM
 * 参数可以恢复**——`-Djava.security.manager=allow` 在 JDK 24+ 会让 JVM 在启动阶段
 * 就失败：
 *
 * {{{
 * Error: A command line option has attempted to allow or enable the Security Manager.
 * }}}
 *
 * <p>于是每个建会话的用例各报一次同样的错，堆栈指向 Hadoop 内部，与本模块的代码
 * 毫无关系：表面上十几个 ERROR，实际只是同一处失败的十余次回声。这里把判断提到
 * 建会话**之前**，一次性说清楚该换哪个 JDK。
 *
 * <p><b>为什么探的是 Hadoop 而不是版本号。</b>压垮会话的只有「本 JDK 下
 * `getCurrentUser()` 还调不调得动」这一件事。探这个能力，既不必维护一张会过期的
 * 版本边界表（JDK 22 / 23 上是否还需要 `allow` 由 JDK 自己决定），
 * 也不需要在本模块里复刻 Hadoop 内部对 JDK 的适配：哪天 Hadoop 改用
 * `Subject.current()` 适配了新版 JDK，这个检查会自然失效，无需改动。
 */
private[spark] object SparkJdkRequirement {

    /**
     * Hadoop 的 `getCurrentUser()` 在当前 JDK 上是否可用。
     *
     * <p>调用它与 Spark 建会话时做的是同一件事，因此不会有副作用误差：
     * 结果被 Hadoop 缓存在静态字段里，Spark 随后取到的是同一份。
     */
    private def hadoopCurrentUserUsable: Boolean =
        try {
            UserGroupInformation.getCurrentUser()
            true
        } catch {
            case _: UnsupportedOperationException => false
        }

    /**
     * 建会话前调用：JDK 不满足时立即失败，并给出可照抄的修复命令。
     */
    def requireHadoopCompatibleJdk(): Unit = {
        if (!hadoopCurrentUserUsable) {
            throw new IllegalStateException(
                s"""当前 JDK（${System.getProperty("java.version")}）跑不了 Spark 3.5 的测试会话。
                   |
                   |Spark 建会话时会取当前用户名，走 Hadoop 的
                   |UserGroupInformation.getCurrentUser()，而它依赖的
                   |javax.security.auth.Subject.getSubject 自 JDK 24 起已被永久移除（JEP 486）。
                   |现象是每个建会话的用例各报一次：
                   |
                   |  java.lang.UnsupportedOperationException: getSubject is not supported
                   |    at org.apache.hadoop.security.UserGroupInformation.getCurrentUser(...)
                   |
                   |这不是配置问题，加参数没有用：-Djava.security.manager=allow 在 JDK 24+
                   |会让 JVM 启动即失败。换用 Spark 3.5 支持的 JDK（17 或 21）重新构建即可，
                   |见 README 第 2 节：
                   |
                   |  JAVA_HOME=$$(/usr/libexec/java_home -v 21) mvn clean package
                   |
                   |本机装了哪些 JDK：/usr/libexec/java_home -V""".stripMargin)
        }
    }
}
