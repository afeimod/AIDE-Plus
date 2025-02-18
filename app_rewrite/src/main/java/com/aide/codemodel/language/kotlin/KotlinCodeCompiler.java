package com.aide.codemodel.language.kotlin;

import com.aide.codemodel.api.FileSpace;
import com.aide.codemodel.api.FileSpace.Assembly;
import com.aide.codemodel.api.Model;
import com.aide.codemodel.api.SyntaxTree;
import com.aide.codemodel.api.abstraction.CodeModel;
import com.aide.codemodel.api.abstraction.Language;
import com.aide.codemodel.api.collections.OrderedMapOfIntInt;
import com.aide.codemodel.api.collections.SetOfInt;
import com.aide.common.AppLog;
import com.google.common.base.Throwables;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.build.report.ICReporterBase;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.incremental.CompilerRunnerUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.zeroaicy.util.reflect.ReflectPie;
import kotlin.io.path.PathsKt;
import kotlin.jvm.functions.Function0;
import kotlin.text.Charsets;
//import io.github.aide.kotlin.KotlinK2JVMCompile;


public class KotlinCodeCompiler implements com.aide.codemodel.api.abstraction.CodeCompiler {

    private final Model model;
    private final MessageCollector mCollector = new Collector();
    private String mainProjectPath;
    private Language language;

    public KotlinCodeCompiler(Model model, Language language) {
        this.model = model;
        this.language = language;
    }

    /**
     * Kt编译需要什么?
     * 1. javaSourceRoots || kotlinSourceRoots
     * AIDE中两者相同 source root找不到，.java .kt文件找得到
     * <p>
     * 2. kotlin-stdlib 通过maven仓库缓存获取
     * 或者有依赖有 kotlin-stdlib-xxx的库 ✔️
     * 3. class依赖库  ✔️
     * 4. 增量编译缓存目录 -> 主项目✔️
     */


    @Override
    public void init(CodeModel codeModel) {
        if (true) return;
        if (!(codeModel instanceof KotlinCodeModel)) {
            return;
        }
        KotlinCodeModel kotlinCodeModel = (KotlinCodeModel) codeModel;

        Model model = kotlinCodeModel.model;
        if (model == null) return;

        FileSpace fileSpace = model.fileSpace;
        ReflectPie fileSpaceReflect = ReflectPie.on(fileSpace);


		/*
		 FunctionOfIntInt fileAssembles = fileSpaceReflect.get("fileAssembles");

		SetOfFileEntry registeredSolutionFiles = fileSpaceReflect.get("registeredSolutionFiles");
		SetOfFileEntry.Iterator registeredSolutionFilesIterator = registeredSolutionFiles.default_Iterator;
		registeredSolutionFilesIterator.init();
		while (registeredSolutionFilesIterator.hasMoreElements()) {
			FileEntry fileEntry = registeredSolutionFilesIterator.nextKey();
			int projectAssemblyId = fileAssembles.get(fileEntry.getId());
			AppLog.println_d("path: %s -> assemblyId: %s ", fileEntry.getPathString(), projectAssemblyId);

		}
		*/

        HashMap<Integer, FileSpace.Assembly> assemblyMap = fileSpaceReflect.get("assemblyMap");

        OrderedMapOfIntInt assemblyReferences = fileSpaceReflect.get("assemblyReferences");
        // AppLog.println_d("assemblyReferences -> %s",  assemblyReferences);

        // OrderedMapOfIntInt允许多个相同的key
        // 应该是 int int 对
        OrderedMapOfIntInt.Iterator default_Iterator = assemblyReferences.default_Iterator;

        // 被依赖的assemblyId
        int mainProjectAssemblyId = findMainProjectAssemblyId(default_Iterator, assemblyMap);
        if (mainProjectAssemblyId < 0) {
            AppLog.println_e("KotlinCodeCompiler找不到主项目");
            return;
        }

        Assembly mainProjectAssembly = assemblyMap.get(mainProjectAssemblyId);
        this.mainProjectPath = Assembly.Zo(mainProjectAssembly);

        default_Iterator.init();

        // 项目依赖映射 都是 项目路径 不是其内部目录依赖
        HashMap<String, Set<Integer>> referenceAssemblysMap = new HashMap<>();
        // 遍历所有 SolutionProject的 AssemblyId
        while (default_Iterator.hasMoreElements()) {
            int projectAssemblyId = default_Iterator.nextKey();
            int referencedProjectAssembly = default_Iterator.nextValue();

            // 自己会依赖自己，排除
            if (projectAssemblyId == referencedProjectAssembly) {
                continue;
            }

            Assembly projectAssembly = assemblyMap.get(projectAssemblyId);

            String projectPath = Assembly.Zo(projectAssembly);

            Set<Integer> references = referenceAssemblysMap.get(projectPath);
            if (references == null) {
                references = new HashSet<>();
                referenceAssemblysMap.put(projectPath, references);
            }

            // Assembly referenceProjectAssembly =  assemblyMap.get(projectAssemblyId);
            // String referenceProjectPath = Assembly.Zo(referenceProjectAssembly);
            references.add(projectAssemblyId);

        }
        // 需要查找出 project的源码目录 // 不需要子依赖
        //

    }

    private int findMainProjectAssemblyId(OrderedMapOfIntInt.Iterator default_Iterator, HashMap<Integer, FileSpace.Assembly> assemblyMap) {
        SetOfInt referencedSet = new SetOfInt();
        // 重置
        default_Iterator.init();
        // 遍历
        while (default_Iterator.hasMoreElements()) {
            int key = default_Iterator.nextKey();
            int referenced = default_Iterator.nextValue();

            // 自己会依赖自己，排除
            if (key != referenced
                    && !referencedSet.contains(referenced)) {
                referencedSet.put(referenced);
            }
        }


        for (Integer assemblyId : assemblyMap.keySet()) {
            // int assemblyId = assemblyIdInteger.intValue();
            if (referencedSet.contains(assemblyId)) {
                continue;
            }
            AppLog.println_d("主项目AssemblyId: ", assemblyId);

            return assemblyId;
        }
        return -1;
    }

    /**
     * 格式化Kotlin代码
     *
     * @param code  这个是格式化的内容
     * @param style 这个是格式化的样式
     *              可以填 "dropbox"，"google"和"kotlinlang"
     * @return
     */
    public String formater(
            String code,
            String style
    ){
        try {
            var fileAttributes = new FileAttribute[0];
            Path path = Files.createTempFile("file", ".kt", Arrays.copyOf(fileAttributes, fileAttributes.length));
            PathsKt.writeText(path, code, Charsets.UTF_8);

            List<String> args = List.of("--style", style, path.toFile().getAbsolutePath());
            new com.facebook.ktfmt.cli.Main(System.in, System.out, System.err, args.toArray(new String[0])).run();
            String formattedCode = PathsKt.readText(path,Charsets.UTF_8);
            Files.deleteIfExists(path);
            return formattedCode;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void compile(List<SyntaxTree> list, boolean p) {
        //List<File> files = new ArrayList<>();
        for (SyntaxTree syntaxTree : list) {
            if (syntaxTree.getLanguage() == this.language) {
                try {
                    String pathString = syntaxTree.getFile().getPathString();
                    System.out.println(pathString);
                    //files.add(new File(pathString));
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
            }
        }


        // Kotlin编译后输出的路径
        File mClassOutput = new File("");

        // 此处是所有依赖的集合
        List<File> classpath = new ArrayList<>();
        //classpath.add("LambdaStubsJarFile")
        //classpath.add("AndroidJarFile")
        // 此处添加项目依赖

        // 转换和添加内容
        List<String> arguments = new ArrayList<>();
        Collections.addAll(arguments, "-cp",
                classpath.stream()
                        .map(File::getAbsolutePath)
                        .collect(Collectors.joining(File.pathSeparator)));

        // Java文件或目录
        String[] javaSourceRoots = new ArrayList<File>()
                // 添加数据
                .stream()
                .map(File::getAbsolutePath)
                .toArray(String[]::new);


        try {
            K2JVMCompiler compiler = new K2JVMCompiler();
            K2JVMCompilerArguments args = new K2JVMCompilerArguments();
            compiler.parseArguments(arguments.toArray(new String[0]), args);


            args.setUseJavac(false);
            args.setUseFastJarFileSystem(true);
            args.setCompileJava(false);
            args.setIncludeRuntime(false);
            args.setNoJdk(true);
            args.setNoReflect(true);
            args.setNoStdlib(true);
            args.setSuppressWarnings(false);
            args.setScript(false);

            args.setModuleName("AIDE-Plus"); // 偷偷加点料（

            args.setJavaSourceRoots(javaSourceRoots); //Java目录

            args.setDestination(mClassOutput.getAbsolutePath()); //Class输出目录

            args.setLanguageVersion("2.1"); //可以自定义，后期扩展
            args.setApiVersion("2.1"); //同上

            args.setJvmTarget("17"); //Java目标输出版本

            //args.setPluginClasspaths();//Kotlin插件的使用，例如ksp(?
            //args.setPluginOptions();// 插件选项

            File cacheDir = new File("", "intermediate/kotlin");

            CompilerRunnerUtils.makeJvmIncrementally(
                    cacheDir,
                    Arrays.asList(new File("")), // 传入java目录
                    args,
                    mCollector,
                    new ICReporterBase() {
                        @Override
                        public void reportCompileIteration(boolean b,
                                                           @NotNull Collection<? extends File> collection,
                                                           @NotNull ExitCode exitCode) {}

                        @Override
                        public void report(@NotNull Function0<String> function0,
                                           @NotNull ReportSeverity reportSeverity) {}
                    });

        } catch (Exception e) {
            throw new RuntimeException(Throwables.getStackTraceAsString(e));
        }

        if (mCollector.hasErrors()) {
            // 包含错误抛出异常，具体逻辑待实现
            throw new RuntimeException("Compilation failed, see logs for more details");
        }


    }


    private static class Collector implements MessageCollector {

        private final List<Object> mDiagnostics = new ArrayList<>(); // 错误列表
        private boolean mHasErrors;

        @Override
        public void clear() {
            mDiagnostics.clear();
        }

        @Override
        public boolean hasErrors() {
            return mHasErrors;
        }

        @Override
        public void report(@NotNull CompilerMessageSeverity severity,
                           @NotNull String message,
                           CompilerMessageSourceLocation location) {
            if (message.contains("No class roots are found in the JDK path")) {
                // Android does not have JDK so its okay to ignore this error
                return;
            }

            // 别忘了添加到错误列表内


            // 这里是错误的详细信息，自己改改吧
            switch (severity) {
                case ERROR:
                    mHasErrors = true;
                    break;
                case STRONG_WARNING:
                case WARNING:
                    break;
                case INFO:
                    break;
                default:
            }
        }
    }
}
