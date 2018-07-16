/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
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
package org.greenrobot.eventbus.annotationprocessor;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import de.greenrobot.common.ListMap;

@SupportedAnnotationTypes("org.greenrobot.eventbus.Subscribe")
@SupportedOptions(value = {"eventBusIndex", "verbose"})
public class EventBusAnnotationProcessor extends AbstractProcessor {
    public static final String OPTION_EVENT_BUS_INDEX = "eventBusIndex";
    public static final String OPTION_VERBOSE = "verbose";

    /** Found subscriber methods for a class (without superclasses). */
    private final ListMap<TypeElement, ExecutableElement> methodsByClass = new ListMap<>();
    private final Set<TypeElement> classesToSkip = new HashSet<>();

    private boolean writerRoundDone;
    private int round;
    private boolean verbose;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        Messager messager = processingEnv.getMessager();
        try {
            // 得到gradle中配置的参数eventBusIndex
            String index = processingEnv.getOptions().get(OPTION_EVENT_BUS_INDEX);
            if (index == null) {
                messager.printMessage(Diagnostic.Kind.ERROR, "No option " + OPTION_EVENT_BUS_INDEX +
                        " passed to annotation processor");
                return false;
            }
            // 输出日志的标记位，可以忽略
            verbose = Boolean.parseBoolean(processingEnv.getOptions().get(OPTION_VERBOSE));
            // 从eventBusIndex中截取我们要使用的包名
            int lastPeriod = index.lastIndexOf('.');
            String indexPackage = lastPeriod != -1 ? index.substring(0, lastPeriod) : null;
            // 记录是第几次执行process方法
            round++;
            if (verbose) {
                messager.printMessage(Diagnostic.Kind.NOTE, "Processing round " + round + ", new annotations: " +
                        !annotations.isEmpty() + ", processingOver: " + env.processingOver());
            }
            if (env.processingOver()) {
                if (!annotations.isEmpty()) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "Unexpected processing state: annotations still available after processing over");
                    return false;
                }
            }
            if (annotations.isEmpty()) {
                return false;
            }

            if (writerRoundDone) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Unexpected processing state: annotations still available after writing.");
            }
            // 收集代码中使用的注解
            collectSubscribers(annotations, env, messager);
            // 检查是否有需要忽略的注解
            checkForSubscribersToSkip(messager, indexPackage);

            if (!methodsByClass.isEmpty()) {
                // 生成源文件
                createInfoIndexFile(index);
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING, "No @Subscribe annotations found");
            }
            writerRoundDone = true;
        } catch (RuntimeException e) {
            // IntelliJ does not handle exceptions nicely, so log and print a message
            e.printStackTrace();
            messager.printMessage(Diagnostic.Kind.ERROR, "Unexpected error in EventBusAnnotationProcessor: " + e);
        }
        return true;
    }

    /**
     * 收集代码中使用的Subscribe注解
     * */
    private void collectSubscribers(Set<? extends TypeElement> annotations, RoundEnvironment env, Messager messager) {
        // 遍历所有要使用的注解
        for (TypeElement annotation : annotations) {
            // 得到这个注解标注的所有元素集合
            Set<? extends Element> elements = env.getElementsAnnotatedWith(annotation);
            for (Element element : elements) {
                // 判断是否是一个可执行的方法
                if (element instanceof ExecutableElement) {
                    ExecutableElement method = (ExecutableElement) element;
                    // 检查方法是否符合要求：非static, 只能是public，只能有一个参数
                    if (checkHasNoErrors(method, messager)) {
                        // 把含有方法的对象保存在集合中
                        TypeElement classElement = (TypeElement) method.getEnclosingElement();
                        methodsByClass.putElement(classElement, method);
                    }
                } else {
                    messager.printMessage(Diagnostic.Kind.ERROR, "@Subscribe is only valid for methods", element);
                }
            }
        }
    }

    /**
     * 检查被注解的元素是否符合要求：非static, 只能是public，只能有一个参数
     * */
    private boolean checkHasNoErrors(ExecutableElement element, Messager messager) {
        if (element.getModifiers().contains(Modifier.STATIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must not be static", element);
            return false;
        }

        if (!element.getModifiers().contains(Modifier.PUBLIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must be public", element);
            return false;
        }

        List<? extends VariableElement> parameters = ((ExecutableElement) element).getParameters();
        if (parameters.size() != 1) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must have exactly 1 parameter", element);
            return false;
        }
        return true;
    }

    /**
     * Subscriber classes should be skipped if their class or any involved event class are not visible to the index.
     *
     *      检查需要被忽略的注解
     */
    private void checkForSubscribersToSkip(Messager messager, String myPackage) {
        // 遍历被注解的元素集合
        for (TypeElement skipCandidate : methodsByClass.keySet()) {
            TypeElement subscriberClass = skipCandidate;
            while (subscriberClass != null) {
                // 判断元素是否可见
                if (!isVisible(myPackage, subscriberClass)) {
                    // 添加到忽略的集合中
                    boolean added = classesToSkip.add(skipCandidate);
                    if (added) {
                        String msg;
                        if (subscriberClass.equals(skipCandidate)) {
                            msg = "Falling back to reflection because class is not public";
                        } else {
                            msg = "Falling back to reflection because " + skipCandidate +
                                    " has a non-public super class";
                        }
                        messager.printMessage(Diagnostic.Kind.NOTE, msg, subscriberClass);
                    }
                    break;
                }
                // 得到这个元素被注解的所有方法
                List<ExecutableElement> methods = methodsByClass.get(subscriberClass);
                if (methods != null) {
                    for (ExecutableElement method : methods) {
                        String skipReason = null;
                        // 获得第一个形参的类型
                        VariableElement param = method.getParameters().get(0);
                        TypeMirror typeMirror = getParamTypeMirror(param, messager);
                        // 如果参数的类型是基本类型，忽略
                        if (!(typeMirror instanceof DeclaredType) ||
                                !(((DeclaredType) typeMirror).asElement() instanceof TypeElement)) {
                            skipReason = "event type cannot be processed";
                        }
                        if (skipReason == null) {
                            // 判断元素是否可见，否则忽略
                            TypeElement eventTypeElement = (TypeElement) ((DeclaredType) typeMirror).asElement();
                            if (!isVisible(myPackage, eventTypeElement)) {
                                skipReason = "event type is not public";
                            }
                        }
                        if (skipReason != null) {
                            // 如果刚才两项检查没通过，加入到忽略的集合中
                            boolean added = classesToSkip.add(skipCandidate);
                            if (added) {
                                String msg = "Falling back to reflection because " + skipReason;
                                if (!subscriberClass.equals(skipCandidate)) {
                                    msg += " (found in super class for " + skipCandidate + ")";
                                }
                                messager.printMessage(Diagnostic.Kind.NOTE, msg, param);
                            }
                            break;
                        }
                    }
                }
                // 找到自己的父类，继续遍历
                subscriberClass = getSuperclass(subscriberClass);
            }
        }
    }

    /**
     * 获得一个参数的类型
     * */
    private TypeMirror getParamTypeMirror(VariableElement param, Messager messager) {
        TypeMirror typeMirror = param.asType();
        // Check for generic type
        if (typeMirror instanceof TypeVariable) {
            // 得到这个元素的父元素
            TypeMirror upperBound = ((TypeVariable) typeMirror).getUpperBound();
            // 如果父元素是被声明的类型，非基本类型
            if (upperBound instanceof DeclaredType) {
                if (messager != null) {
                    messager.printMessage(Diagnostic.Kind.NOTE, "Using upper bound type " + upperBound +
                            " for generic parameter", param);
                }
                typeMirror = upperBound;
            }
        }
        return typeMirror;
    }

    /**
     * 获得指定元素的父类
     * */
    private TypeElement getSuperclass(TypeElement type) {
        if (type.getSuperclass().getKind() == TypeKind.DECLARED) {
            TypeElement superclass = (TypeElement) processingEnv.getTypeUtils().asElement(type.getSuperclass());
            String name = superclass.getQualifiedName().toString();
            // 如果父类是java类或者是android类，检查结束
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")) {
                // Skip system classes, this just degrades performance
                return null;
            } else {
                return superclass;
            }
        } else {
            return null;
        }
    }

    /**
     * 获得一个类的class
     * */
    private String getClassString(TypeElement typeElement, String myPackage) {
        // 得到元素的包名
        PackageElement packageElement = getPackageElement(typeElement);
        String packageString = packageElement.getQualifiedName().toString();
        String className = typeElement.getQualifiedName().toString();
        // 如果包名并不为空
        if (packageString != null && !packageString.isEmpty()) {
            // 如果与我们创建的文件的包名相同，裁剪掉包名，只留下类名
            if (packageString.equals(myPackage)) {
                className = cutPackage(myPackage, className);
            }
            // 如果是java原生类，直接使用类名
            else if (packageString.equals("java.lang")) {
                className = typeElement.getSimpleName().toString();
            }
        }
        // 其他情况使用完整的包名+类名
        return className;
    }

    private String cutPackage(String paket, String className) {
        if (className.startsWith(paket + '.')) {
            // Don't use TypeElement.getSimpleName, it doesn't work for us with inner classes
            return className.substring(paket.length() + 1);
        } else {
            // Paranoia
            throw new IllegalStateException("Mismatching " + paket + " vs. " + className);
        }
    }

    private PackageElement getPackageElement(TypeElement subscriberClass) {
        Element candidate = subscriberClass.getEnclosingElement();
        while (!(candidate instanceof PackageElement)) {
            candidate = candidate.getEnclosingElement();
        }
        return (PackageElement) candidate;
    }

    /**
     * 创建一个SimpleSubscriberInfo对象
     * */
    private void writeCreateSubscriberMethods(BufferedWriter writer, List<ExecutableElement> methods,
                                              String callPrefix, String myPackage) throws IOException {
        for (ExecutableElement method : methods) {
            List<? extends VariableElement> parameters = method.getParameters();
            TypeMirror paramType = getParamTypeMirror(parameters.get(0), null);
            TypeElement paramElement = (TypeElement) processingEnv.getTypeUtils().asElement(paramType);
            String methodName = method.getSimpleName().toString();
            String eventClass = getClassString(paramElement, myPackage) + ".class";

            Subscribe subscribe = method.getAnnotation(Subscribe.class);
            List<String> parts = new ArrayList<>();
            parts.add(callPrefix + "(\"" + methodName + "\",");
            String lineEnd = "),";
            if (subscribe.priority() == 0 && !subscribe.sticky()) {
                if (subscribe.threadMode() == ThreadMode.POSTING) {
                    parts.add(eventClass + lineEnd);
                } else {
                    parts.add(eventClass + ",");
                    parts.add("ThreadMode." + subscribe.threadMode().name() + lineEnd);
                }
            } else {
                parts.add(eventClass + ",");
                parts.add("ThreadMode." + subscribe.threadMode().name() + ",");
                parts.add(subscribe.priority() + ",");
                parts.add(subscribe.sticky() + lineEnd);
            }
            writeLine(writer, 3, parts.toArray(new String[parts.size()]));

            if (verbose) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Indexed @Subscribe at " +
                        method.getEnclosingElement().getSimpleName() + "." + methodName +
                        "(" + paramElement.getSimpleName() + ")");
            }

        }
    }

    /**
     * 生成源文件
     *
     * @param index 指定的生成文件的名称
     * */
    private void createInfoIndexFile(String index) {
        BufferedWriter writer = null;
        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(index);
            int period = index.lastIndexOf('.');
            String myPackage = period > 0 ? index.substring(0, period) : null;
            String clazz = index.substring(period + 1);
            writer = new BufferedWriter(sourceFile.openWriter());
            if (myPackage != null) {
                writer.write("package " + myPackage + ";\n\n");
            }
            writer.write("import org.greenrobot.eventbus.meta.SimpleSubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberMethodInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberInfoIndex;\n\n");
            writer.write("import org.greenrobot.eventbus.ThreadMode;\n\n");
            writer.write("import java.util.HashMap;\n");
            writer.write("import java.util.Map;\n\n");
            writer.write("/** This class is generated by EventBus, do not edit. */\n");
            // 实现SubscriberInfoIndex接口
            writer.write("public class " + clazz + " implements SubscriberInfoIndex {\n");
            writer.write("    private static final Map<Class<?>, SubscriberInfo> SUBSCRIBER_INDEX;\n\n");
            // static代码块，把刚才找到的注解信息，直接保存在集合中
            writer.write("    static {\n");
            writer.write("        SUBSCRIBER_INDEX = new HashMap<Class<?>, SubscriberInfo>();\n\n");
            writeIndexLines(writer, myPackage);
            writer.write("    }\n\n");
            // 加入一个注解信息
            writer.write("    private static void putIndex(SubscriberInfo info) {\n");
            writer.write("        SUBSCRIBER_INDEX.put(info.getSubscriberClass(), info);\n");
            writer.write("    }\n\n");
            writer.write("    @Override\n");
            // 获得指定class的注解信息
            writer.write("    public SubscriberInfo getSubscriberInfo(Class<?> subscriberClass) {\n");
            writer.write("        SubscriberInfo info = SUBSCRIBER_INDEX.get(subscriberClass);\n");
            writer.write("        if (info != null) {\n");
            writer.write("            return info;\n");
            writer.write("        } else {\n");
            writer.write("            return null;\n");
            writer.write("        }\n");
            writer.write("    }\n");
            writer.write("}\n");
        } catch (IOException e) {
            throw new RuntimeException("Could not write source for " + index, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    //Silent
                }
            }
        }
    }

    /**
     * 把找到的注解信息封装起来
     * */
    private void writeIndexLines(BufferedWriter writer, String myPackage) throws IOException {
        for (TypeElement subscriberTypeElement : methodsByClass.keySet()) {
            // 如果这是一个被忽略的元素，直接跳过
            if (classesToSkip.contains(subscriberTypeElement)) {
                continue;
            }

            String subscriberClass = getClassString(subscriberTypeElement, myPackage);
            // 如果元素是可见的
            if (isVisible(myPackage, subscriberTypeElement)) {
                // 把注解信息封装成SimpleSubscriberInfo，放入集合中
                writeLine(writer, 2,
                        "putIndex(new SimpleSubscriberInfo(" + subscriberClass + ".class,",
                        "true,", "new SubscriberMethodInfo[] {");
                List<ExecutableElement> methods = methodsByClass.get(subscriberTypeElement);
                writeCreateSubscriberMethods(writer, methods, "new SubscriberMethodInfo", myPackage);
                writer.write("        }));\n\n");
            } else {
                writer.write("        // Subscriber not visible to index: " + subscriberClass + "\n");
            }
        }
    }

    /**
     * 判断元素是否是全局可见：public或者在app的包名中，或者是最外层类，不属于任何包
     * */
    private boolean isVisible(String myPackage, TypeElement typeElement) {
        Set<Modifier> modifiers = typeElement.getModifiers();
        boolean visible;
        if (modifiers.contains(Modifier.PUBLIC)) {
            visible = true;
        } else if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.PROTECTED)) {
            visible = false;
        } else {
            String subscriberPackage = getPackageElement(typeElement).getQualifiedName().toString();
            if (myPackage == null) {
                visible = subscriberPackage.length() == 0;
            } else {
                visible = myPackage.equals(subscriberPackage);
            }
        }
        return visible;
    }

    private void writeLine(BufferedWriter writer, int indentLevel, String... parts) throws IOException {
        writeLine(writer, indentLevel, 2, parts);
    }

    private void writeLine(BufferedWriter writer, int indentLevel, int indentLevelIncrease, String... parts)
            throws IOException {
        writeIndent(writer, indentLevel);
        int len = indentLevel * 4;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i != 0) {
                if (len + part.length() > 118) {
                    writer.write("\n");
                    if (indentLevel < 12) {
                        indentLevel += indentLevelIncrease;
                    }
                    writeIndent(writer, indentLevel);
                    len = indentLevel * 4;
                } else {
                    writer.write(" ");
                }
            }
            writer.write(part);
            len += part.length();
        }
        writer.write("\n");
    }

    private void writeIndent(BufferedWriter writer, int indentLevel) throws IOException {
        for (int i = 0; i < indentLevel; i++) {
            writer.write("    ");
        }
    }
}
