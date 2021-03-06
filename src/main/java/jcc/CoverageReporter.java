package jcc;

import org.apache.tools.ant.DirectoryScanner;
import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Принимаемый проект скомпилирован,а также содержит классы тестов в каких-либо .jar-файлах
 * Тесты принимаемого проекта реализуют интерфейс Runnable.
 */

public class CoverageReporter {

    private final URLClassLoader baseClassLoader;

    private final MemoryClassLoader instrAndTestsClassLoader;

    private final List<JarFile> jarFiles;

    private final String classesPackage;

    private final String testsPackage;

    public CoverageReporter(String[] args) throws IOException {

        if (args == null || args.length < 3) throw new IllegalArgumentException("Incorrect data entered");

        String projectDirPath = args[0];
        File projectDir = new File(projectDirPath);

        if (!projectDir.exists()) throw new IllegalArgumentException("Project doesn't exist");

        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setIncludes(new String[]{"**/*.jar"}); // for any OS
        scanner.setBasedir(projectDir);
        scanner.scan();
        String[] scannedJarPaths = scanner.getIncludedFiles();
        int length = scannedJarPaths.length;

        URL[] scannedJarURLs = new URL[length];
        List<JarFile> scannedJarFiles = new ArrayList<>(length);
        System.out.println("Scanned .jar(s):");
        for (int i = 0; i < scannedJarPaths.length; i++) {
            String absolutePath = projectDirPath + "/" + scannedJarPaths[i];
            System.out.println(absolutePath);
            scannedJarURLs[i] = new URL("file:" + absolutePath);
            scannedJarFiles.add(new JarFile(absolutePath));
        }

        this.baseClassLoader = new URLClassLoader(scannedJarURLs);
        this.instrAndTestsClassLoader = new MemoryClassLoader(baseClassLoader);
        this.jarFiles = scannedJarFiles;
        this.classesPackage = args[1];
        this.testsPackage = args[2];

    }

    public void execute() throws Exception {

        final IRuntime runtime = new LoggerRuntime();

        List<String> classes = new ArrayList<>();

        List<String> tests = new ArrayList<>();

        InputStream original;

        System.out.println("\nScanned Classes:");
        for (JarFile jarFile : jarFiles) {
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                String name = jarEntry.getName();
                if (name.endsWith(".class")) {
                    if (name.contains(testsPackage)) {
                        String fullyQualifiedName = getFullyQualifiedName(name);
                        System.out.println(name + " - " + fullyQualifiedName);
                        original = baseClassLoader.getResourceAsStream(name);
                        instrAndTestsClassLoader.addDefinition(fullyQualifiedName, original.readAllBytes());
                        tests.add(name);
                    } else if (name.contains(classesPackage)){
                        String fullyQualifiedName = getFullyQualifiedName(name);
                        System.out.println(name + " - " + fullyQualifiedName);
                        original = baseClassLoader.getResourceAsStream(name);
                        final Instrumenter instr = new Instrumenter(runtime);
                        final byte[] instrumented = instr.instrument(original, fullyQualifiedName);
                        original.close();
                        instrAndTestsClassLoader.addDefinition(fullyQualifiedName, instrumented);
                        classes.add(name);
                    }
                }
            }
        }

        final RuntimeData data = new RuntimeData();
        runtime.startup(data);

        System.out.println("\nRunning tests...");
        for (String testName : tests) {
            final Class<?> testClass = instrAndTestsClassLoader.loadClass(getFullyQualifiedName(testName));
            final Runnable targetInstance = (Runnable) testClass.getDeclaredConstructor().newInstance();
            targetInstance.run();
        }

        System.out.println("\nAnalyzing Coverage...");

        final ExecutionDataStore executionData = new ExecutionDataStore();
        final SessionInfoStore sessionInfos = new SessionInfoStore();
        data.collect(executionData, sessionInfos, false);
        runtime.shutdown();

        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);

        for (String className : classes) {
            original = baseClassLoader.getResourceAsStream(className);
            analyzer.analyzeClass(original, getFullyQualifiedName(className));
            original.close();
        }

        for (final IClassCoverage cc : coverageBuilder.getClasses()) {
            String className = cc.getName();
            System.out.printf("%nCoverage of class %s:%n", className);

            printCounter("instructions", cc.getInstructionCounter());
            printCounter("branches", cc.getBranchCounter());
            printCounter("lines", cc.getLineCounter());
            printCounter("methods", cc.getMethodCounter());
            printCounter("complexity", cc.getComplexityCounter());

            System.out.printf("%nCoverage of %s methods:%n", className);

            for (final IMethodCoverage mc : cc.getMethods()) {
                System.out.printf("%nCoverage of method %s:%n", mc.getName());

                printCounter("instructions", mc.getInstructionCounter());
                printCounter("branches", mc.getBranchCounter());
                printCounter("lines", mc.getLineCounter());
                printCounter("methods", mc.getMethodCounter());
                printCounter("complexity", mc.getComplexityCounter());
            }
        }

    }

    private String getFullyQualifiedName(String name) {
        return name.substring(0, name.length() - 6).replace('/', '.');
    }

    private void printCounter(final String unit, final ICounter counter) {
        final Integer covered = counter.getCoveredCount();
        final Integer total = counter.getTotalCount();
        System.out.printf("%s of %s %s covered%n", covered, total, unit);
    }

    public static void main(String[] args) throws Exception{
        new CoverageReporter(args).execute();
    }

    public static class MemoryClassLoader extends ClassLoader {

        private final URLClassLoader parent;

        public MemoryClassLoader(URLClassLoader parent) {
            this.parent = parent;
        }

        private final Map<String, byte[]> definitions = new HashMap<>();

        public void addDefinition(final String name, final byte[] bytes) {
            definitions.put(name, bytes);
        }

        @Override
        public Class<?> loadClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = definitions.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return parent.loadClass(name);
        }

    }
}
