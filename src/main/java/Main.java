import generator.ASTMWEGenerator;
import generator.AbstractMWEGenerator;
import org.apache.commons.io.FileUtils;
import testexecutor.TestExecutorOptions;

import java.io.File;

public class Main {

	public static void main(String[] args) {

		AbstractMWEGenerator generator;
		if(args.length >= 5) {
			TestExecutorOptions options = new TestExecutorOptions()
					.withModulePath(args[0])
					.withSourceFolderPath(args[1])
					.withUnitTestFolderPath(args[2])
					.withUnitTestMethod(args[3])
					.withExpectedResult(args[4])
					//.withCompilationType(TestExecutorOptions.ECompilationType.COMMAND_LINE)
					.withLogCompilationErrors(false)
					.withLogRuntimeErrors(false)
					.withNumberOfThreads(10)
					.withPreSliceCode(false)
					.withLogging(TestExecutorOptions.ELogLevel.INFO);

			generator = new ASTMWEGenerator(options);
		} else {
			generator = new ASTMWEGenerator(Constants.CALCULATOR_OPTIONS_MULTI);
		}

		try {
			long start = System.currentTimeMillis();

			generator.runGenerator();

			long time = System.currentTimeMillis() - start;
			System.out.println();
			System.out.println("TOTAL EXECUTION TIME: " + time + " ms.");

			// check output size:
			String dir = System.getProperty("user.dir");
			long outputSize = FileUtils.sizeOfDirectory(new File(dir + "/testingoutput"));
			System.out.println("TOTAL OUTPUT SIZE: " + outputSize + " bytes");
		} catch (Exception e) {
			e.printStackTrace(System.out);
		}
	}
}
