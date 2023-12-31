package testexecutor;

import compiler.InMemoryJavaCompiler;
import fragment.ICodeFragment;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.text.edits.TextEdit;
import org.mdkt.compiler.CompilationException;
import utility.FileUtility;
import utility.SlicerUtility;
import utility.StatsTracker;
import utility.StatsUtility;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class ATestExecutor implements ITestExecutor {

	private final TestExecutorOptions m_options;
	protected final AtomicInteger m_compilerCalls = new AtomicInteger();
	protected final AtomicInteger m_compilationErrors = new AtomicInteger();
	protected final AtomicInteger m_runtimeErrors = new AtomicInteger();
	protected final AtomicInteger m_failedRuns = new AtomicInteger();
	protected final AtomicInteger m_okRuns = new AtomicInteger();
	private static final String JAVA_VERSION = "1.8";

	protected ATestExecutor(TestExecutorOptions options) {
		m_options = options;

		System.out.println("Using executor " + this.getClass().getSimpleName() + " with executor options:\n" + options.toString());
	}

	protected TestExecutorOptions getOptions() {
		return m_options;
	}

	protected void writeConfigurationToTestingFolder(List<ICodeFragment> fragments, Path folderPath) throws IOException {
		File testSourceFolder = getSourceFolder(folderPath, getOptions().getSourceFolderPath());
		for (Map.Entry<String, String> file : mapFragmentsToFiles(fragments).entrySet()) {
			String fileName = file.getKey();
			File newFile = new File(testSourceFolder.getPath() + fileName);
			if (!newFile.createNewFile()) {
				throw new TestingException("Unable to recreate file " + fileName);
			}
			FileOutputStream fos = new FileOutputStream(newFile);
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos));
			writer.write(file.getValue());
			writer.close();
		}
	}

	protected abstract Map<String, String> mapFragmentsToFiles(List<ICodeFragment> fragments);

	protected File getSourceFolder(Path modulePath, String sourceFolderPath) {
		File moduleFolder = new File(modulePath.toString());
		if (!moduleFolder.exists() || !moduleFolder.isDirectory()) {
			throw new ExtractorException("Invalid module path " + modulePath);
		}
		File sourceFolder = new File(moduleFolder.getPath() + File.separator + sourceFolderPath);
		if (!sourceFolder.exists() || !sourceFolder.isDirectory()) {
			throw new ExtractorException("Missing source folder " + sourceFolder);
		}
		return sourceFolder;
	}

	protected Path getTestFolderPath() {
		String dir = System.getProperty("user.dir");
		return FileSystems.getDefault().getPath(dir + File.separator + "testingfolder");
	}

	protected Path getTestSourcePath() {
		String dir = System.getProperty("user.dir");
		return FileSystems.getDefault().getPath(dir + File.separator + "testingsource");
	}

	protected Path getTestBuildPath() {
		String dir = System.getProperty("user.dir");
		return FileSystems.getDefault().getPath(dir + File.separator + "testingbuild");
	}

	protected Path getTestOutputPath() {
		String dir = System.getProperty("user.dir");
		return FileSystems.getDefault().getPath(dir + File.separator + "testingoutput");
	}

	protected void copyFolderStructure(Path src, Path dest, boolean includeJavaFiles) throws IOException {
		try (Stream<Path> stream = Files.walk(src)) {
			stream.filter(path -> includeJavaFiles || !Files.isRegularFile(path) || !"java".equals(FilenameUtils.getExtension(path.toString())))
					.forEach(source -> copy(source, dest.resolve(src.relativize(source))));
		}
	}

	protected void copy(Path source, Path dest) {
		try {
			Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	@Override
	public ETestResult test(List<ICodeFragment> fragments) {
		switch (getOptions().getCompilationType().toString()) {
			case "IN_MEMORY":
				return testInMemory(fragments);
			case "COMMAND_LINE":
				return testWithCommandLine(fragments);
			default:
				throw new TestingException("Unknown compilation type");
		}
	}

	public void recreateCode(List<ICodeFragment> fragments) {
		Path testOutputPath = getTestOutputPath();
		try {
			FileUtility.deleteFolder(testOutputPath);
			copyFolderStructure(getTestSourcePath(), testOutputPath, false);

			// Copy unit test file
			String unitTestFolderPath = getOptions().getUnitTestFolderPath();
			Path unitTestPath = FileSystems.getDefault().getPath(testOutputPath + File.separator + unitTestFolderPath);
			FileUtility.deleteFolder(unitTestPath);
			copyFolderStructure(FileSystems.getDefault().getPath(getTestSourcePath() + File.separator + unitTestFolderPath), unitTestPath, true);
		} catch (IOException e) {
			throw new TestingException("Unable to copy module", e);
		}

		// write files to output folder
		try {
			writeConfigurationToTestingFolder(fragments, testOutputPath);
		} catch (IOException e) {
			throw new TestingException("Unable to write configuration to testing folder", e);
		}
	}

	@Override
	public void changeSourceToOutputFolder() {
		getOptions().withModulePath(getTestOutputPath().toString());
	}

	protected static Object instantiateUnitTest(Class<?> unitTestClass) throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
		Object unitTest;
		Constructor<?>[] constructors = unitTestClass.getConstructors();
		Constructor<?> noParamsConstructor;
		if (constructors.length == 0) {
			noParamsConstructor = unitTestClass.getDeclaredConstructor();
		} else {
			noParamsConstructor = Stream.of(constructors)
					.filter(c -> c.getParameterTypes().length == 0)
					.findFirst()
					.orElse(null);
		}
		if (noParamsConstructor != null) {
			noParamsConstructor.setAccessible(true);
			unitTest = noParamsConstructor.newInstance();
		} else {
			Constructor<?> firstConstructor = Stream.of(constructors)
					.min(Comparator.comparing(c -> c.getParameterTypes().length))
					.orElse(null);

			Object[] args = new Object[firstConstructor.getParameterTypes().length];
			for (int i = 0; i < firstConstructor.getParameterTypes().length; i++) {
				Class<?> clz = firstConstructor.getParameterTypes()[i];
				Constructor<?> constructor = clz.getDeclaredConstructor();
				constructor.setAccessible(true);
				args[i] = constructor.newInstance();
			}
			unitTest = firstConstructor.newInstance(args);

		}
		return unitTest;
	}

	protected ETestResult testInMemory(List<ICodeFragment> fragments) {
		InMemoryJavaCompiler compiler = InMemoryJavaCompiler
				.newInstance()
				.ignoreWarnings();
		String[] unitTestName = getOptions().getUnitTestMethod().split("#");

		try {
			Path unitTestFolder = FileSystems.getDefault().getPath(getTestSourcePath().toString() + File.separator + getOptions().getUnitTestFolderPath());
			FileUtility.addJavaFilesToCompiler(compiler, unitTestFolder);
			for (Map.Entry<String, String> file : mapFragmentsToFiles(fragments).entrySet()) {
				compiler.addSource(FileUtility.fileNameToClassName(file.getKey()), file.getValue());
			}
		} catch (Exception e) {
			throw new TestingException("Error while adding files", e);
		}
		Map<String, Class<?>> classes;

		try {
			m_compilerCalls.incrementAndGet();
			classes = compiler.compileAll();
		} catch (CompilationException e) {
			if(m_options.isLogCompilationErrors()) {
				System.out.println("############ Compilation error: ############ \n" + e);
			}
			m_compilationErrors.incrementAndGet();
			return ETestResult.ERROR_COMPILATION;
		} catch (Exception e) {
			throw new TestingException("Error during compilation", e);
		}


		// execute unit test
		Class<?> unitTestClass = classes.get(unitTestName[0]);
		try {
			Object unitTest = instantiateUnitTest(unitTestClass);

			Method testingMethod;
			try {
				testingMethod = unitTestClass.getDeclaredMethod(unitTestName[1]);
			} catch (NoSuchMethodException e) {
				// method might be declared on a parent class
				testingMethod = unitTestClass.getMethod(unitTestName[1]);
			}
			testingMethod.setAccessible(true);

			try {
				Method finalTestingMethod = testingMethod;
				ExecutorService executor = Executors.newSingleThreadExecutor();
				executor.submit(() -> finalTestingMethod.invoke(unitTest)).get(2, TimeUnit.MINUTES);
				m_okRuns.incrementAndGet();
				return ETestResult.OK;
			} catch (Exception ex) {
				if (ex.getCause() != null &&
						(ex.getCause().toString() != null && ex.getCause().toString().contains(getOptions().getExpectedResult())
								|| ex.getCause().getCause() != null && ex.getCause().getCause().toString() != null && ex.getCause().getCause().toString().contains(getOptions().getExpectedResult()))) {
					m_failedRuns.incrementAndGet();
					return ETestResult.FAILED;
				} else {
					if (m_options.isLogRuntimeErrors()) {
						System.out.println("Code execution runtime error:");
						ex.printStackTrace(System.out);
					}
					m_runtimeErrors.incrementAndGet();
					return ETestResult.ERROR_RUNTIME;
				}
			}
		} catch (Exception e) {
			throw new TestingException("Error during test execution", e);
		}
	}

	protected ETestResult testWithCommandLine(List<ICodeFragment> fragments) {
		Path testSourcePath = getTestSourcePath();
		Path testFolderPath = getTestFolderPath();
		Path testBuildPath = getTestBuildPath();
		try {
			FileUtility.deleteFolder(testFolderPath);
			FileUtility.deleteFolder(testBuildPath);
			copyFolderStructure(testSourcePath, testFolderPath, false);

			// Copy unit test folder
			String unitTestFolderPath = getOptions().getUnitTestFolderPath();
			Path unitTestPath = FileSystems.getDefault().getPath(testFolderPath + File.separator + unitTestFolderPath);
			FileUtility.deleteFolder(unitTestPath);
			copyFolderStructure(FileSystems.getDefault().getPath(testSourcePath + File.separator + unitTestFolderPath), unitTestPath, true);
		} catch (IOException e) {
			throw new TestingException("Unable to copy module", e);
		}

		// write files to testing folder
		try {
			writeConfigurationToTestingFolder(fragments, getTestFolderPath());
		} catch (IOException e) {
			throw new TestingException("Unable to write configuration to testing folder", e);
		}

		// compile java classes for testing
		String classPath = System.getProperty("java.class.path");
		classPath = Arrays.stream(classPath.split(";")).filter(
				dependency -> dependency.endsWith(".jar") && !dependency.contains("JetBrains")
		).collect(Collectors.joining(";"));

		String javaHome = System.getProperty("java.home");

		final List<String> commands = new ArrayList<>();
		commands.add("cmd.exe");
		commands.add("/C");
		commands.add("cd testingfolder");
		commands.add("& dir /s /B *.java > testingsources.txt"); // write paths of all java files to sources file
		commands.add("& findstr /v \"^$\" testingsources.txt > temp.txt & move /y temp.txt testingsources.txt >nul "); // remove empty lines from sources file
		commands.add("& " + javaHome + "/bin/javac -d ../testingbuild -cp " + classPath + " @testingsources.txt"); // compile all java files

		ProcessBuilder pb = new ProcessBuilder(commands);
		Process p;
		try {
			p = pb.start();
			if (!p.waitFor(1, TimeUnit.MINUTES)) {
				throw new TestingException("Timed out while compiling java code");
			}
			if (p.exitValue() > 0) {
				return ETestResult.ERROR_COMPILATION;
			}
		} catch (IOException | InterruptedException e) {
			throw new TestingException("Unexpected error while compiling java code", e);
		}

		// run unit test
		commands.clear();
		commands.add("cmd.exe");
		commands.add("/C");
		commands.add(javaHome + "/bin/java -Dfile.encoding=UTF-8 " +
				"-classpath " + testBuildPath + ";" + classPath +
				" org.junit.platform.console.ConsoleLauncher --select-method " + getOptions().getUnitTestMethod());

		ProcessBuilder pb2 = new ProcessBuilder(commands);
		Process p2;
		try {
			p2 = pb2.start();
			if (!p2.waitFor(1, TimeUnit.MINUTES)) {
				throw new TestingException("Timed out while running testing code");
			}
			if (p2.exitValue() > 0) {
				String input = IOUtils.toString(p2.getInputStream(), StandardCharsets.UTF_8);
				String error = IOUtils.toString(p2.getErrorStream(), StandardCharsets.UTF_8);
				if (error != null && error.length() > 0) {
					if (error.contains(getOptions().getExpectedResult())) {
						return ETestResult.FAILED;
					} else {
						return ETestResult.ERROR_RUNTIME;
					}
				}
				if (input.contains(getOptions().getExpectedResult())) {
					return ETestResult.FAILED;
				}
				return ETestResult.ERROR_RUNTIME;
			}
		} catch (IOException | InterruptedException e) {
			throw new TestingException("Unexpected error while executing unit test", e);
		}

		return ETestResult.OK;
	}

	public void initialize() {
		// copy source folder
		Path testSourcePath = getTestSourcePath();
		try {
			FileUtility.deleteFolder(testSourcePath);
			copyFolderStructure(FileSystems.getDefault().getPath(getOptions().getModulePath()), testSourcePath, true);
		} catch (IOException e) {
			throw new TestingException("Unable to copy module", e);
		}

		if (m_options.isPreSliceCode()) {
			SlicerUtility.doSlicing(testSourcePath, m_options);
		}

		System.out.println("Formatting code in source folder");
		formatModuleFolder(testSourcePath);

		StatsUtility.trackInputSize(getSourceFolder(testSourcePath, getOptions().getSourceFolderPath()));
	}

	public String getStatistics() {
		return String.format("Compiler calls: %d, compilation errors: %d, runtime errors: %d, failed runs: %d, ok runs: %d",
				m_compilerCalls.get(), m_compilationErrors.get(), m_runtimeErrors.get(), m_failedRuns.get(), m_okRuns.get());
	}

	protected boolean isExcludedFile(Path path) {
		return path == null || path.endsWith("package-info.java");
	}

	public void formatOutputFolder() {
		Path outputPath = getTestOutputPath();
		formatModuleFolder(outputPath);
		StatsUtility.trackOutputSize(getSourceFolder(outputPath, getOptions().getSourceFolderPath()));
	}

	protected void formatModuleFolder(Path modulePath) {
		final Map<String, String> options = new HashMap<>();
		options.put(JavaCore.COMPILER_SOURCE, JAVA_VERSION);
		options.put(JavaCore.COMPILER_COMPLIANCE, JAVA_VERSION);
		options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JAVA_VERSION);
		CodeFormatter formatter = ToolFactory.createCodeFormatter(options, ToolFactory.M_FORMAT_EXISTING);
		File testSourceFolder = getSourceFolder(modulePath, getOptions().getSourceFolderPath());
		try (Stream<Path> walk = Files.walk(testSourceFolder.toPath())) {
			walk.forEach(path -> {
				try {
					if (!Files.isRegularFile(path) || !"java".equals(FilenameUtils.getExtension(path.toString()))) {
						return;
					}
					String code = new String(Files.readAllBytes(path));
					IRegion region = new Region(0, code.length());
					TextEdit edit = formatter.format(CodeFormatter.K_COMPILATION_UNIT, code, new IRegion[]{region}, 0, "\n");
					IDocument doc = new Document(code);
					edit.apply(doc);
					String formattedCode = doc.get();
					if (code.equals(formattedCode)) {
						return;
					}
					FileOutputStream fos = new FileOutputStream(path.toFile(), false);
					fos.write(formattedCode.getBytes(StandardCharsets.UTF_8));
					fos.close();
				} catch (Exception e) {
					System.out.println("Unable to format file " + path.toString());
					e.printStackTrace(System.out);
				}
			});
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void trackDDminCompilerStats() {
		StatsTracker statsTracker = StatsUtility.getStatsTracker();
		statsTracker.trackDDMinCompilerStats(m_compilerCalls.get(), m_failedRuns.get());
	}
}
