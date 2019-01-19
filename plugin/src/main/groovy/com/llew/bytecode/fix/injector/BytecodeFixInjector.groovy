package com.llew.bytecode.fix.injector

import com.llew.bytecode.fix.bean.BytecodeFixConfig
import com.llew.bytecode.fix.extension.BytecodeFixExtension
import com.llew.bytecode.fix.task.BuildJarTask
import com.llew.bytecode.fix.utils.FileUtils
import com.llew.bytecode.fix.utils.Logger
import com.llew.bytecode.fix.utils.TextUtil
import javassist.ClassPool
import javassist.CtClass
import javassist.CtMethod
import org.gradle.api.Project

import java.util.jar.JarFile
import java.util.zip.ZipFile
/**
 * 字节码注入器
 * <p>
 * <br/><br/>
 *
 * @author llew
 * @date 2017/11/16
 */

public class BytecodeFixInjector {

    public static final String INJECTOR   = "injector"
    public static final String JAVA       = ".java"
    public static final String CLASS      = ".class"
    public static final String JAR        = ".jar"

    private static ClassPool sClassPool
    private static BytecodeFixInjector sInjector

    private Project mProject
    private String mVersionName
    private BytecodeFixExtension mExtension

    private BytecodeFixInjector(Project project, String versionName, BytecodeFixExtension extension) {
        this.mProject = project
        this.mVersionName = versionName
        this.mExtension = extension
        appendDefaultClassPath()
    }

    public static void init(Project project, String versionName, BytecodeFixExtension extension) {
        sClassPool = ClassPool.default
        sInjector = new BytecodeFixInjector(project, versionName, extension)
    }

    public static BytecodeFixInjector getInjector() {
        if (null == sInjector) {
            throw new IllegalAccessException("init() hasn't been called !!!")
        }
        return sInjector
    }

    public synchronized File inject(File jar, BytecodeFixConfig bytecodeFixConfig) {
        File destFile = null

        if (null == mExtension) {
            Logger.e("can't find bytecodeFixConfig in your app build.gradle !!!")
            return destFile
        }

        if (!mExtension.enable) {
            Logger.e("bytecodeFix not enabled !!!")
            return destFile
        }

        if (null == bytecodeFixConfig) {
            Logger.e("fixConfig invalid !!!")
            return destFile
        }

        if (null == jar) {
            Logger.e("jar File is null before injecting !!!")
            return destFile
        }

        if (!jar.exists()) {
            Logger.e(jar.name + " not exits !!!")
            return destFile
        }

        try {
            ZipFile zipFile = new ZipFile(jar)
            zipFile.close()
            zipFile = null
        } catch (Exception ignored) {
            Logger.e(jar.name + " not a valid jar file !!!")
            return destFile
        }

        def jarName = jar.name.substring(0, jar.name.length() - JAR.length())
        def baseDir = new StringBuilder().append(mProject.projectDir.absolutePath)
                .append(File.separator).append(INJECTOR)
                .append(File.separator).append(mVersionName)
                .append(File.separator).append(jarName).toString()

        File rootFile = new File(baseDir)
        FileUtils.clearFile(rootFile)
        if (!rootFile.mkdirs()) {
            Logger.e("mkdirs ${rootFile.absolutePath} failure")
        }

        File unzipDir = new File(rootFile, "classes")
        File jarDir   = new File(rootFile, "jar")

        JarFile jarFile = new JarFile(jar)

        bytecodeFixConfig.classInfoList.each { classInfo ->
            if (null != classInfo && !TextUtil.isEmpty(classInfo.className)) {

                def className = classInfo.className

                def contain = FileUtils.containsClass(jarFile, className)

                if (contain) {
                    // 1、判断是否进行过解压缩操作
                    if (!FileUtils.hasFiles(unzipDir)) {
                        FileUtils.unzipJarFile(jarFile, unzipDir)

                        // 2、开始注入文件，需要注意的是，appendClassPath后边跟的根目录，没有后缀，className后完整类路径，也没有后缀
                        sClassPool.appendClassPath(unzipDir.absolutePath)
                    }

                    // 3、开始注入代码，去除.class后缀
                    if (className.endsWith(CLASS)) {
                        className = className.substring(0, className.length() - CLASS.length())
                    }

                    CtClass ctClass = sClassPool.getCtClass(className)

                    if (!ctClass.isInterface() && !ctClass.isAnnotation() && !ctClass.isEnum()) {

                        classInfo.methodInfoList.each { methodInfo ->

                            def methodName  = methodInfo.methodName
                            def methodArgs  = methodInfo.methodArgs
                            def injectLine  = methodInfo.injectLine
                            def injectValue = methodInfo.injectValue


                            CtMethod ctMethod
                            if (methodArgs.isEmpty()) {
                                ctMethod = ctClass.getDeclaredMethod(methodName)
                            } else {
                                def size = methodArgs.size()
                                CtClass[] params = new CtClass[size]
                                for (int i = 0; i < size; i++) {
                                    String param = methodArgs.get(i)
                                    params[i] = sClassPool.getCtClass(param)
                                }
                                ctMethod = ctClass.getDeclaredMethod(methodName, params)
                            }

                            if ("{}".equals(injectValue)) {
                                CtClass exceptionType = sClassPool.get("java.lang.Throwable")
                                String returnValue = "{\$e.printStackTrace();return null;}"
                                CtClass returnType = ctMethod.getReturnType()
                                if (CtClass.booleanType == returnType) {
                                    returnValue = "{\$e.printStackTrace();return false;}"
                                } else if (CtClass.voidType == returnType) {
                                    returnValue = "{\$e.printStackTrace();return;}"
                                } else if (CtClass.byteType == returnType || CtClass.shortType == returnType || CtClass.charType == returnType || CtClass.intType == returnType || CtClass.floatType == returnType || CtClass.doubleType == returnType || CtClass.longType == returnType) {
                                    returnValue = "{\$e.printStackTrace();return 0;}"
                                } else {
                                    returnValue = "{\$e.printStackTrace();return null;}"
                                }
                                ctMethod.addCatch(returnValue, exceptionType)
                            } else {
                                if (injectLine > 0) {
                                    ctMethod.insertAt(injectLine, injectValue)
                                } else if (injectLine == 0) {
                                    ctMethod.insertBefore(injectValue)
                                } else {
                                    if (!injectValue.startsWith("{")) {
                                        injectValue = "{" + injectValue
                                    }
                                    if (!injectValue.endsWith("}")) {
                                        injectValue = injectValue + "}"
                                    }
                                    ctMethod.setBody(injectValue)
                                }
                            }
                        }

                        // 4、循环完毕，写入文件
                        ctClass.writeFile(unzipDir.absolutePath)
                        ctClass.detach()
                    }
                }
            }
        }

        // 5、循环体结束，判断classes文件夹下是否有文件
        if (FileUtils.hasFiles(unzipDir)) {
            BuildJarTask buildJarTask = mProject.tasks.create("BytecodeFixBuildJarTask", BuildJarTask)
            buildJarTask.baseName = jarName
            buildJarTask.from(unzipDir.absolutePath)
            buildJarTask.doLast {
                // 进行文件的拷贝
                def stringBuilder = new StringBuilder().append(mProject.projectDir.absolutePath)
                        .append(File.separator).append("build")
                        .append(File.separator).append("libs")
                        .append(File.separator).append(jar.name).toString()

                if (!jarDir.exists()) {
                    jarDir.mkdirs()
                }

                destFile = new File(jarDir, jar.name)
                FileUtils.clearFile(destFile)
                destFile.createNewFile()

                File srcFile = new File(stringBuilder)
                com.android.utils.FileUtils.copyFile(srcFile, destFile)
                FileUtils.clearFile(srcFile)

                if (null != mExtension && !mExtension.keepFixedClassFile) {
                    FileUtils.clearFile(unzipDir)
                }
            }
            // FIXME buildJarTask sometimes has bug
            // buildJarTask.execute()

            destFile = new File(jarDir, jar.name)
            FileUtils.clearFile(destFile)
            FileUtils.zipJarFile(unzipDir, destFile)

            if (null != mExtension && !mExtension.keepFixedClassFile) {
                FileUtils.clearFile(unzipDir)
            }
        } else {
            FileUtils.clearFile(rootFile)
        }

        jarFile.close()

        return destFile
    }

    private void appendDefaultClassPath() {
        if (null == mProject) return
        def androidJar = new StringBuffer().append(mProject.android.getSdkDirectory())
                .append(File.separator).append("platforms")
                .append(File.separator).append(mProject.android.compileSdkVersion)
                .append(File.separator).append("android.jar").toString()

        File file = new File(androidJar);
        if (!file.exists()) {
            androidJar = new StringBuffer().append(mProject.rootDir.absolutePath)
                    .append(File.separator).append("local.properties").toString()

            Properties properties = new Properties()
            properties.load(new File(androidJar).newDataInputStream())

            def sdkDir = properties.getProperty("sdk.dir")

            androidJar = new StringBuffer().append(sdkDir)
                    .append(File.separator).append("platforms")
                    .append(File.separator).append(mProject.android.compileSdkVersion)
                    .append(File.separator).append("android.jar").toString()

            file = new File(androidJar)
        }

        if (file.exists()) {
            sClassPool.appendClassPath(androidJar);
        } else {
            Logger.e("couldn't find android.jar file !!!")
        }
    }

    public void appendClassPath(File path) {
        if (null != path) {
            if (path.directory) {
                sClassPool.appendPathList(path.absolutePath)
            } else {
                sClassPool.appendClassPath(path.absolutePath)
            }
        }
    }
}
