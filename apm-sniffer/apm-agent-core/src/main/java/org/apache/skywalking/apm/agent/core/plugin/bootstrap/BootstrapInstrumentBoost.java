/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.agent.core.plugin.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.pool.TypePool;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.AbstractClassEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.ByteBuddyCoreClasses;
import org.apache.skywalking.apm.agent.core.plugin.InstrumentDebuggingClass;
import org.apache.skywalking.apm.agent.core.plugin.PluginException;
import org.apache.skywalking.apm.agent.core.plugin.PluginFinder;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.v2.InstanceMethodsInterceptV2Point;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.v2.StaticMethodsInterceptV2Point;
import org.apache.skywalking.apm.agent.core.plugin.jdk9module.JDK9ModuleExporter;
import org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * If there is Bootstrap instrumentation plugin declared in plugin list, BootstrapInstrumentBoost inject the necessary
 * classes into bootstrap class loader, including generated dynamic delegate classes.
 */
public class BootstrapInstrumentBoost {
    private static final ILog LOGGER = LogManager.getLogger(BootstrapInstrumentBoost.class);

    private static final String[] HIGH_PRIORITY_CLASSES = {
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.BootstrapInterRuntimeAssist",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceConstructorInterceptor",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.StaticMethodsAroundInterceptor",
        "org.apache.skywalking.apm.agent.core.plugin.bootstrap.IBootstrapLog",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.OverrideCallable",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult",

        // interceptor v2
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.v2.InstanceMethodsAroundInterceptorV2",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.v2.StaticMethodsAroundInterceptorV2",
        "org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.v2.MethodInvocationContext",
    };

    private static String INSTANCE_METHOD_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.InstanceMethodInterTemplate";
    private static String INSTANCE_METHOD_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.InstanceMethodInterWithOverrideArgsTemplate";
    private static String CONSTRUCTOR_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.ConstructorInterTemplate";
    private static String STATIC_METHOD_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.StaticMethodInterTemplate";
    private static String STATIC_METHOD_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.StaticMethodInterWithOverrideArgsTemplate";

    private static String INSTANCE_METHOD_V2_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.v2.InstanceMethodInterV2Template";
    private static String INSTANCE_METHOD_V2_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.v2.InstanceMethodInterV2WithOverrideArgsTemplate";
    private static String STATIC_METHOD_V2_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.v2.StaticMethodInterV2Template";
    private static String STATIC_METHOD_V2_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE = "org.apache.skywalking.apm.agent.core.plugin.bootstrap.template.v2.StaticMethodInterV2WithOverrideArgsTemplate";

    public static AgentBuilder inject(PluginFinder pluginFinder, Instrumentation instrumentation,
        AgentBuilder agentBuilder, JDK9ModuleExporter.EdgeClasses edgeClasses) throws PluginException {
        // 所有要注入到 bootstrap classloader 里面的类
        Map<String, byte[]> classesTypeMap = new HashMap<>();

        /*
            针对目标类是 JDK 核心类库的插件，这里根据插件的拦截点的不同（实例方法、静态方法、构造方法）
            使用不同的模板（XXXTemplate）来定义新的拦截器的核心处理逻辑，并且将插件本身定义的拦截器的全类名
            赋值给模板的 TARGET_INTERCEPTOR 字段

            最终，这些新的拦截器的核心处理逻辑都会被放入 Bootstrap classloader中

            !! 为什么增加JDK内部类需要这么做呢？
            - 这个问题暂时未知，需要再考虑！！可能是安全或者其他？？
         */
        if (!prepareJREInstrumentation(pluginFinder, classesTypeMap)) {
            return agentBuilder;
        }

        if (!prepareJREInstrumentationV2(pluginFinder, classesTypeMap)) {
            return agentBuilder;
        }

        for (String highPriorityClass : HIGH_PRIORITY_CLASSES) {
            loadHighPriorityClass(classesTypeMap, highPriorityClass);
        }
        for (String highPriorityClass : ByteBuddyCoreClasses.CLASSES) {
            loadHighPriorityClass(classesTypeMap, highPriorityClass);
        }

        /**
         * Prepare to open edge of necessary classes.
         */
        for (String generatedClass : classesTypeMap.keySet()) {
            edgeClasses.add(generatedClass);
        }

        /**
         * Inject the classes into bootstrap class loader by using Unsafe Strategy.
         * ByteBuddy adapts the sun.misc.Unsafe and jdk.internal.misc.Unsafe automatically.
         */
        // 将类注入到 bootstrap classloader中
        // 为什么需要注入？因为某些agent插件会修改 bootstrap classloader 加载的类，所以需要将这些插件对应的类注入到 bootstrap classloader 中

        // 1. ClassInjector.UsingUnsafe表示使用Unsafe方式注入类
        // 这种方式可以绕过JVM的常规类加载限制
        ClassInjector.UsingUnsafe.Factory factory = ClassInjector.UsingUnsafe.Factory.resolve(instrumentation);
        // 2. factory.make创建注入器实例
        // 两个null参数分别是:
        // - 第一个null: 目标ClassLoader(null表示使用bootstrap classloader)
        // - 第二个null: 保护域(ProtectionDomain)
        // 3. injectRaw注入原始字节码
        // classesTypeMap是一个Map<TypeDescription, byte[]>
        // key: 类的类型描述
        // value: 类的字节码
        factory.make(null, null).injectRaw(classesTypeMap);
        agentBuilder = agentBuilder.with(new AgentBuilder.InjectionStrategy.UsingUnsafe.OfFactory(factory));

        return agentBuilder;
    }

    /**
     * Get the delegate class name.
     *
     * @param methodsInterceptor of original interceptor in the plugin
     * @return generated delegate class name
     */
    public static String internalDelegate(String methodsInterceptor) {
        return methodsInterceptor + "_internal";
    }

    /**
     * Load the delegate class from current class loader, mostly should be AppClassLoader.
     *
     * @param methodsInterceptor of original interceptor in the plugin
     * @return generated delegate class
     */
    public static Class forInternalDelegateClass(String methodsInterceptor) {
        try {
            return Class.forName(internalDelegate(methodsInterceptor));
        } catch (ClassNotFoundException e) {
            throw new PluginException(e.getMessage(), e);
        }
    }

    /**
     * Generate dynamic delegate for ByteBuddy
     *
     * @param pluginFinder   gets the whole plugin list.
     * @param classesTypeMap hosts the class binary.    需要加载到bootStrapClassloader中的类
     * @return true if have JRE instrumentation requirement.
     * @throws PluginException when generate failure.
     */
    private static boolean prepareJREInstrumentation(PluginFinder pluginFinder,
        Map<String, byte[]> classesTypeMap) throws PluginException {
        // 1. 构建一个TypePool。这个TypePool能加载指定classloader中的所有对象
        TypePool typePool = TypePool.Default.of(BootstrapInstrumentBoost.class.getClassLoader());
        // 2. 获取到需要对jdk核心类库增强的插件
        List<AbstractClassEnhancePluginDefine> bootstrapClassMatchDefines = pluginFinder.getBootstrapClassMatchDefine();
        for (AbstractClassEnhancePluginDefine define : bootstrapClassMatchDefines) {
            if (Objects.nonNull(define.getInstanceMethodsInterceptPoints())) {
                for (InstanceMethodsInterceptPoint point : define.getInstanceMethodsInterceptPoints()) {
                    if (point.isOverrideArgs()) {
                        generateDelegator(
                            classesTypeMap, typePool, INSTANCE_METHOD_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE, point
                                .getMethodsInterceptor());
                    } else {
                        // INSTANCE_METHOD_DELEGATE_TEMPLATE 表示生成类的模板
                        generateDelegator(
                            classesTypeMap, typePool, INSTANCE_METHOD_DELEGATE_TEMPLATE, point.getMethodsInterceptor());
                    }
                }
            }

            if (Objects.nonNull(define.getConstructorsInterceptPoints())) {
                for (ConstructorInterceptPoint point : define.getConstructorsInterceptPoints()) {
                    generateDelegator(
                        classesTypeMap, typePool, CONSTRUCTOR_DELEGATE_TEMPLATE, point.getConstructorInterceptor());
                }
            }

            if (Objects.nonNull(define.getStaticMethodsInterceptPoints())) {
                for (StaticMethodsInterceptPoint point : define.getStaticMethodsInterceptPoints()) {
                    if (point.isOverrideArgs()) {
                        generateDelegator(
                            classesTypeMap, typePool, STATIC_METHOD_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE, point
                                .getMethodsInterceptor());
                    } else {
                        generateDelegator(
                            classesTypeMap, typePool, STATIC_METHOD_DELEGATE_TEMPLATE, point.getMethodsInterceptor());
                    }
                }
            }
        }
        return bootstrapClassMatchDefines.size() > 0;
    }

    private static boolean prepareJREInstrumentationV2(PluginFinder pluginFinder,
                                                       Map<String, byte[]> classesTypeMap) throws PluginException {
        TypePool typePool = TypePool.Default.of(BootstrapInstrumentBoost.class.getClassLoader());
        List<AbstractClassEnhancePluginDefine> bootstrapClassMatchDefines = pluginFinder.getBootstrapClassMatchDefine();
        for (AbstractClassEnhancePluginDefine define : bootstrapClassMatchDefines) {
            if (Objects.nonNull(define.getInstanceMethodsInterceptV2Points())) {
                for (InstanceMethodsInterceptV2Point point : define.getInstanceMethodsInterceptV2Points()) {
                    if (point.isOverrideArgs()) {
                        generateDelegator(classesTypeMap, typePool,
                                          INSTANCE_METHOD_V2_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE,
                                          point.getMethodsInterceptorV2()
                        );
                    } else {
                        generateDelegator(
                            classesTypeMap, typePool, INSTANCE_METHOD_V2_DELEGATE_TEMPLATE,
                            point.getMethodsInterceptorV2()
                        );
                    }
                }
            }

            if (Objects.nonNull(define.getStaticMethodsInterceptV2Points())) {
                for (StaticMethodsInterceptV2Point point : define.getStaticMethodsInterceptV2Points()) {
                    if (point.isOverrideArgs()) {
                        generateDelegator(classesTypeMap, typePool,
                                          STATIC_METHOD_V2_WITH_OVERRIDE_ARGS_DELEGATE_TEMPLATE,
                                          point.getMethodsInterceptorV2()
                        );
                    } else {
                        generateDelegator(
                            classesTypeMap, typePool, STATIC_METHOD_V2_DELEGATE_TEMPLATE,
                            point.getMethodsInterceptorV2()
                        );
                    }
                }
            }
        }
        return bootstrapClassMatchDefines.size() > 0;
    }

    /**
     * Generate the delegator class based on given template class. This is preparation stage level code generation.
     * <p>
     * One key step to avoid class confliction between AppClassLoader and BootstrapClassLoader
     *
     * @param classesTypeMap    hosts injected binary of generated class
     * @param typePool          to generate new class
     * @param templateClassName represents the class as template in this generation process. The templates are
     *                          pre-defined in SkyWalking agent core.
     */
    private static void generateDelegator(Map<String, byte[]> classesTypeMap, TypePool typePool,
        String templateClassName, String methodsInterceptor) {
        String internalInterceptorName = internalDelegate(methodsInterceptor);
        try {
            // 获取 typePool 类加载器下classpath下的类定义
            TypeDescription templateTypeDescription = typePool.describe(templateClassName).resolve();
            // 根据模板源文件 templateTypeDescription 重新定义一个类
            // ClassFileLocator的作用是定位和读取类文件，它只是告诉ByteBuddy从哪里找到模板类的字节码，用于读取类的结构信息，如方法、字段等
            DynamicType.Unloaded interceptorType = new ByteBuddy().redefine(templateTypeDescription, ClassFileLocator.ForClassLoader
                .of(BootstrapInstrumentBoost.class.getClassLoader()))
                                                                  .name(internalInterceptorName) // 类的名称
                                                                  .field(named("TARGET_INTERCEPTOR"))   // 设定 TARGET_INTERCEPTOR 的值为原始的plugin名称
                                                                  .value(methodsInterceptor)
                                                                  .make();
            // 拿到 make 的类的字节码
            classesTypeMap.put(internalInterceptorName, interceptorType.getBytes());

            InstrumentDebuggingClass.INSTANCE.log(interceptorType);
        } catch (Exception e) {
            throw new PluginException("Generate Dynamic plugin failure", e);
        }
    }

    /**
     * The class loaded by this method means it only should be loaded once in Bootstrap classloader, when bootstrap
     * instrumentation active by any plugin
     *
     * @param loadedTypeMap hosts all injected class
     * @param className     to load
     */
    private static void loadHighPriorityClass(Map<String, byte[]> loadedTypeMap,
        String className) throws PluginException {
        byte[] enhancedInstanceClassFile;
        try {
            String classResourceName = className.replaceAll("\\.", "/") + ".class";
            InputStream resourceAsStream = AgentClassLoader.getDefault().getResourceAsStream(classResourceName);

            if (resourceAsStream == null) {
                throw new PluginException("High priority class " + className + " not found.");
            }

            ByteArrayOutputStream os = new ByteArrayOutputStream();

            byte[] buffer = new byte[1024];
            int len;

            // read bytes from the input stream and store them in buffer
            while ((len = resourceAsStream.read(buffer)) != -1) {
                // write bytes from the buffer into output stream
                os.write(buffer, 0, len);
            }

            enhancedInstanceClassFile = os.toByteArray();
        } catch (IOException e) {
            throw new PluginException(e.getMessage(), e);
        }

        loadedTypeMap.put(className, enhancedInstanceClassFile);
    }
}
