/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2014 Spirent Communications, Inc.
 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.spirent.plugins.itest;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BatchFile;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import net.sf.json.JSONObject;

/**
 * Parse test execution options for iTestCLI and iTestRT. 
 * 
 * @author Spirent 
 * @since 1.0
 */
public class ITest extends CommandInterpreter { 

	/**
	 * @since 1.0
	 */
	public final String workspace; 
	/**
	 * @since 1.0
	 */
	public final String testcases; 
	/**
	 * @since 1.0
	 */
	public final String testbed;  
	/**
	 * @since 1.0
	 */
	public final String params;
	/**
	 * @since 1.0
	 */
	public final String paramFile; 
	/**
	 * @since 1.0
	 */
	public final boolean testReportRequired; 
	/**
	 * @since 1.0
	 */
	public final String dbCustomTag; 

	private final static boolean BUILD_FAILURE = false; 
    private final static boolean BUILD_SUCCESS = true;
    private static final String URI_PROJECT = "project://";
    private static final String URI_FILE = "file:/";
    private static final String VAR_WORKSPACE = "${WORKSPACE}";

	private transient String safeTestbed = ""; 
	private transient String safeParamFile = ""; 

	private transient String iTestCommand = ""; 
	private transient String itestrt = ""; 
	private transient ArrayList<String> testCaseNames; 

    private final String PARAM_TESTBED = "--testbed";
    private final String PARAM_PARAMETER = "--paramfile";
    private final String PARAM_ITAR = " --itar";
    private final String PARAM_EXPORTITAR = "--exportItar";
    private final String PARAM_LICENSE_SERVER = "--licenseServer";
    private final String PARAM_TEST = "--test";

    private final String PATTERN_EXECUTION = "Execution status:\\s+(\\w+)";
    private String SPACE_CHARACTER = "%%20";


	@DataBoundConstructor
    public ITest(String workspace, String testcases, String testbed, String params, String paramFile,
			boolean testReportRequired, String dbCustomTag) {
		super(null);
        this.workspace = workspace.trim();
        this.testcases = testcases.trim();
        this.testbed = testbed.trim();
		this.params = params;
        this.paramFile = paramFile.trim();
		this.testReportRequired = testReportRequired;
		this.dbCustomTag = dbCustomTag;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) {

        if (launcher.isUnix()) {
            SPACE_CHARACTER = "%20";
        }

		ITest.Descriptor global = new ITest.Descriptor();  
        testCaseNames = new ArrayList<String>();
        if (global.getRtPath().isEmpty()) {
            this.itestrt = "itestrt";
        } else if (global.getRtPath().contains(" ")) {
            this.itestrt = String.format("\"%s\"", global.getRtPath());
        } else {
            itestrt = global.getRtPath();
        }

		processBuildWorkspace(build); 

        //        if (!canGenerateITARFile(build, launcher, listener)) {
        //            return BUILD_FAILURE;
        //        }

		String licenseServerURI = global.lsIPAddress; 
		if(!global.lsPort.isEmpty()) { 
			licenseServerURI += ":" + global.lsPort; 
		}

        iTestCommand = String.format("%s %s %s %s \"%s\"", this.itestrt, this.PARAM_LICENSE_SERVER, licenseServerURI, this.PARAM_ITAR, parseWorkspace(build));

		addTestExecutionOptions(); 
		parseTestCases(build); 

		if (!testReportRequired) { 
            if (executeCommand(iTestCommand, build, launcher, listener) && testPassed(build)) {
                return BUILD_SUCCESS;
			}
		} else { 
            if (canInitializeReport(build, launcher, listener) //
                    && executeCommand(iTestCommand, build, launcher, listener) //
                    && canFinalizeReport(build, launcher, listener) //
                    && testPassed(build)) {
                return BUILD_SUCCESS;
			}
		}

        return BUILD_FAILURE;
	}

	/**
	 * Expand environment variables for ${WORKSPACE}. 
	 * @param build
	 * @param src 
	 */
	private void processBuildWorkspace(AbstractBuild<?, ?> build) {

        String front = String.valueOf(build.getWorkspace());

        if (!testbed.isEmpty()) {
            if (testbed.startsWith(VAR_WORKSPACE)) {
                String back = testbed.replace(VAR_WORKSPACE, "");
                safeTestbed = front + back;
            } else if (!testbed.startsWith(URI_PROJECT) && !testbed.startsWith(URI_FILE)) {
                safeTestbed = String.format("%s%s", URI_FILE, this.testbed);
            } else {
                safeTestbed = testbed;
            }
            this.safeTestbed = this.safeTestbed.replace(" ", SPACE_CHARACTER);
        }

        if (!paramFile.isEmpty()) {
            if (paramFile.startsWith(VAR_WORKSPACE)) {
                String back = paramFile.replace(VAR_WORKSPACE, "");
                safeParamFile = front + back;
            } else if (!paramFile.startsWith(URI_PROJECT) && !paramFile.startsWith(URI_FILE)) {
                safeParamFile = String.format("%s%s", URI_FILE, this.paramFile);
            } else {
                safeParamFile = paramFile;
            }
            this.safeParamFile = this.safeParamFile.replace(" ", SPACE_CHARACTER);
        }
	}

	/**
	 * Run iTestRT commands. 
	 * 
	 * @param command
	 * @param build
	 * @param launcher
	 * @param listener
	 */
	private boolean executeCommand(final String command, final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) { 

		String uniformPathSeparators = command.replaceAll("\\\\", "/"); 
		CommandInterpreter runner = 
                getCommandInterpreter(launcher, uniformPathSeparators);
		try {
			runner.perform(build, launcher, listener);
            return true;
		} catch (InterruptedException e) {
			e.printStackTrace();
            return false;
		}

	}

	/**
	 * Determine if relative or absolute workspace path was provided. 
	 * @param build 
	 */
	private String parseWorkspace(final AbstractBuild<?, ?> build) { 

        String front = String.valueOf(build.getWorkspace());

        if (workspace.equals(VAR_WORKSPACE)) {
            return front;
        } else if (workspace.startsWith(VAR_WORKSPACE)) {
            return String.format("%s%s", front, this.workspace.replace(VAR_WORKSPACE, ""));
        } else {
            return this.workspace;
		}
	}

	private String expandEnvironmentVariables(String src){
		String res = src;
		Map<String, String> env = System.getenv();
        for (Entry<String, String> iter : env.entrySet()) {
            res = res.replace("\\$" + iter.getKey(), iter.getValue());
		}
		return res;
	}

	/**
	 * Prepare workspace to generate Spirent iTest test reports. 
	 * @param build
	 * @param launcher
	 * @param listener
	 * @return true if successful 
	 */
	private boolean canInitializeReport(final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) { 

		FilePath test = build.getWorkspace();
		String test2 = "" + test; //convert FilePath to String 
		String safeWorkspacePath = expandEnvironmentVariables(
				test2.replaceAll("\\\\", "/")); 
		String buildID = build.getId(); //to set up build folders 

		//create directory to store report (no harm if already exists) 
		String createTestReportDir = "pushd . & cd " + safeWorkspacePath 
				+ " & mkdir jenkins_test_reports_" + buildID + " & popd";

		if (!executeCommand(createTestReportDir, build, launcher, listener)){ 
			return BUILD_FAILURE; 
		}

		try {
			iTestCommand += " --report " + test.toURI()
					+ "jenkins_test_reports_" + buildID 
					+ "/{tcfilename}.html";
		} catch (Exception e) {
			e.printStackTrace();
			return BUILD_FAILURE; 
		} 

		addTestReportDatabaseOptions(); 
		return BUILD_SUCCESS; 
	}

	/**
	 * Publish HTML reports in Jenkins. 
	 * @param build
	 * @param launcher
	 * @param listener
	 * @return true if successful
	 */
	private boolean canFinalizeReport(final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) { 

        String safeWorkspacePath = String.valueOf(build.getWorkspace()).replaceAll("\\\\", "/");
		String buildID = build.getId(); //to set up build folders 
        String displayName = "Spirent iTest Report"; //name of display link 
        String reportDir = safeWorkspacePath + "/jenkins_test_reports_" + buildID; //one dir per build 
        List<Report> list = new ArrayList<Report>();

		for (String s : testCaseNames) { 
			String testcaseName = s.substring(s.lastIndexOf("/") + 1, 
					s.lastIndexOf(".")); 
            String report = displayName + "-" + testcaseName;
			list.add(new Report(report, reportDir, 
					testcaseName + ".html", true, true)); 
		}

		ReportPublisher publisher = new ReportPublisher(list);

		try { 
			publisher.perform(build, launcher, listener); 
		} catch (InterruptedException e) {
			e.printStackTrace();
			return BUILD_FAILURE; 
		} 

		return BUILD_SUCCESS; 
	}

	/**
	 * Check console output for any error messages before proceeding. 
	 * @param build
	 * @param launcher
	 * @param listener
	 */
	private boolean consoleOutputIsValid(final AbstractBuild<?, ?> build) {  
		File test = build.getLogFile();
		Scanner scanner = null;
		try {
            scanner = new Scanner(test, "UTF-8");
			while (scanner.hasNextLine()) { 
				String nextLine = scanner.nextLine(); 
				//error messages generated by iTestCLI and iTestRT 
				if (nextLine.contains("Error") 
						|| nextLine.contains("cannot find the path")
						|| nextLine.contains("valid directory")
						|| nextLine.contains("No project to be exported")
						|| nextLine.contains("Failed to generate report")) { 
					return false; 
				}
			} 
		} catch (FileNotFoundException e) {
			e.printStackTrace();
            return false;
		} finally { 
            if (scanner != null)
                scanner.close();
		}

		return true; 
	}

	/**
	 * Determine if all any test cases have failed. 
	 * @param build
	 * @return
	 */
	private boolean testPassed(final AbstractBuild<?, ?> build) {  
		File test = build.getLogFile();
		Scanner scanner = null;
        Pattern p = Pattern.compile(PATTERN_EXECUTION);
        Matcher m = null;
		try {
            scanner = new Scanner(test, "UTF-8");
			while (scanner.hasNextLine()) { 
                String nextLine = scanner.nextLine().trim();
                if (nextLine.startsWith("Error") //
                        || nextLine.contains("cannot find the path") //
                        || nextLine.contains("valid directory") //
                        || nextLine.contains("Failed to generate report")) {
                    return false;
                }
                m = p.matcher(nextLine);
                if (m.find()) {
                    if (!m.group(1).equalsIgnoreCase("Pass")) {
                        return false;
                    }
                }
            }
            return true;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} finally { 
            if (scanner != null)
                scanner.close();
		}
        return false;
	}

	/**
	 * Generate iTAR files using iTestCLI. 
	 * 
	 * @param build
	 * @param launcher
	 * @param listener
	 */
    private boolean canGenerateITARFile(final AbstractBuild<?, ?> build, 
			final Launcher launcher, final BuildListener listener) { 

		String path = parseWorkspace(build); 
        String generateITAR = String.format("%s %s \"%s\" %s", this.itestrt, this.PARAM_ITAR, path, this.PARAM_EXPORTITAR);

        CommandInterpreter runner = 
				getCommandInterpreter(launcher, generateITAR); 

		try { 
			runner.perform(build, launcher, listener);
		} catch (Exception e) { 
			e.printStackTrace();
		}

		return consoleOutputIsValid(build); 
	}

	/**
	 * Each test case must be preceded by --test option. 
	 */
	private void parseTestCases(final AbstractBuild<?, ?> build) { 

        String[] multipleTestCases = testcases.split(",");
        String front = String.valueOf(build.getWorkspace());
		String back = null, temp = null; 

		for (String testCase: multipleTestCases) {
            if (testCase.trim().isEmpty()) {
                continue;
            }
            testCase = testCase.trim();
            if (testCase.startsWith(VAR_WORKSPACE)) {
                back = testCase.replace(VAR_WORKSPACE, "");
                temp = String.format("%s%s%s", URI_FILE, front, back);
            } else if (!testCase.startsWith(URI_PROJECT) && !testCase.startsWith(URI_FILE)) {
                temp = String.format("%s%s", URI_FILE, testCase);
            } else {
                temp = testCase;
			}
            testCaseNames.add(temp);
            temp = temp.replace(" ", SPACE_CHARACTER);
            iTestCommand += String.format(" %s %s", this.PARAM_TEST, temp);
		}
	}

	/**
	 * Parse test execution options. 
	 */
	private void addTestExecutionOptions() { 
		if (!testbed.isEmpty()) { 
            iTestCommand += String.format(" %s \"%s\"", this.PARAM_TESTBED, this.safeTestbed);
		}

		if (!params.isEmpty()) { 
            String[] multipleParams = params.split(",");
			for(String param : multipleParams) { 
                iTestCommand += String.format(" --param \"%s\"", param.trim());
			}
		}

		if (!paramFile.isEmpty()) { 
            iTestCommand += String.format(" %s \"%s\"", this.PARAM_PARAMETER, this.safeParamFile);
		}
	}

	/**
	 * Parse test report database options. 
	 */
	private void addTestReportDatabaseOptions() { 

		//to access static nested class fields
		ITest.Descriptor global = new Descriptor();   

		if (!global.dbUsername.isEmpty()) { 
			iTestCommand += " --trdb.user " + global.dbUsername; 
			iTestCommand += " --trdb.password " + global.dbPassword; 

			if(!dbCustomTag.isEmpty()) { 
				iTestCommand += " --tag " + dbCustomTag; 
			}

			iTestCommand += " --host " + global.lsIPAddress; 

			if(!global.dbURI.isEmpty()) { 
				iTestCommand += " --uri " + global.dbURI; 
				return; 
			}

			iTestCommand += " --catalog " + global.dbName; 
			iTestCommand += " --dbtype " + global.dbType; 
			iTestCommand += " --ipaddr " + global.dbIPAddress; 
			iTestCommand += " --trdb.port " + global.dbPort; 
		}
	}

	/**
	 * Return correct CommandInterpreter based on OS  
	 * 
	 * @param launcher
	 * @param script
	 * @return CommandInterpreter
	 */
	private CommandInterpreter getCommandInterpreter(final Launcher launcher,
			final String script) {
		if (launcher.isUnix())
			return new Shell(script);
		else
			return new BatchFile(script);
	}

	@Extension
	public static final class Descriptor extends BuildStepDescriptor<Builder> {

		/**
		 * @since 1.0
		 */
		private String rtPath; 
		/**
		 * @since 1.0
		 */
		private String lsIPAddress; 
		/**
		 * @since 1.0
		 */
		private String lsPort; 
		/**
		 * @since 1.0
		 */
		private String dbName;
		/**
		 * @since 1.0
		 */
		private String dbType; 
		/**
		 * @since 1.0
		 */
		private String dbUsername;
		/**
		 * @since 1.0
		 */
		private String dbPassword;
		/**
		 * @since 1.0
		 */
		private String dbURI;
		/**
		 * @since 1.0
		 */
		private String dbIPAddress;
		/**
		 * @since 1.0
		 */
		private String dbPort;
		/**
		 * @return the rtPath
		 */
		public String getRtPath() {
            return rtPath.trim();
		}

		/**
		 * @return the license server ip or host 
		 */
		public String getLsIPAddress() {
			return lsIPAddress;
		}

		/**
		 * @return the license server port number 
		 */
		public String getLsPort() { 
			return lsPort; 
		}

		/**
		 * @return the dbName
		 */
		public String getDbName() {
			return dbName;
		}

		/**
		 * @return the dbType
		 */
		public String getDbType() {
			return dbType;
		}

		/**
		 * Set dbType. 
		 * @param type
		 */
		public void setDbType(String type){ 
			dbType = type; 
		}

		/**
		 * @return the dbUsername
		 */
		public String getDbUsername() {
			return dbUsername;
		}

		/**
		 * @return the dbPassword
		 */
		public String getDbPassword() {
			return dbPassword;
		}

		/**
		 * @return the dbURI
		 */
		public String getDbURI() {
			return dbURI;
		}

		/**
		 * @return the dbIPAddress
		 */
		public String getDbIPAddress() {
			return dbIPAddress;
		}

		/**
		 * @return the dbPort
		 */
		public String getDbPort() {
			return dbPort;
		}

		/**
		 * @param rtPath the rtPath to set
		 */
		public void setRtPath(String rtPath) {
            this.rtPath = rtPath;
		}

		/**
		 * @param lsIPAddress the lsIPAddress to set
		 */
		public void setLsIPAddress(String lsIPAddress) {
			this.lsIPAddress = lsIPAddress;
		}

		/**
		 * @param lsPort the lsPort to set
		 */
		public void setLsPort(String lsPort) {
			this.lsPort = lsPort;
		}

		/**
		 * @param dbName the dbName to set
		 */
		public void setDbName(String dbName) {
			this.dbName = dbName;
		}

		/**
		 * @param dbUsername the dbUsername to set
		 */
		public void setDbUsername(String dbUsername) {
			this.dbUsername = dbUsername;
		}

		/**
		 * @param dbPassword the dbPassword to set
		 */
		public void setDbPassword(String dbPassword) {
			this.dbPassword = dbPassword;
		}

		/**
		 * @param dbURI the dbURI to set
		 */
		public void setDbURI(String dbURI) {
			this.dbURI = dbURI;
		}

		/**
		 * @param dbIPAddress the dbIPAddress to set
		 */
		public void setDbIPAddress(String dbIPAddress) {
			this.dbIPAddress = dbIPAddress;
		}

		/**
		 * @param dbPort the dbPort to set
		 */
		public void setDbPort(String dbPort) {
			this.dbPort = dbPort;
		}

		@Override
		public String getDisplayName() {
			return "Execute Spirent iTest test case"; 
		}

		public Descriptor() {
			load();
		}

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true; 
		}

        public Descriptor(String rtPath, String lsIPAddress,
				String lsPort, String dbName, String dbType,
				String dbUsername, String dbPassword,
				String dbURI, String dbIPAddress, String dbPort) {
			super();
			this.rtPath = rtPath;
			this.lsIPAddress = lsIPAddress;
			this.lsPort = lsPort;
			this.dbName = dbName;
			this.dbType = dbType;
			this.dbUsername = dbUsername;
			this.dbPassword = dbPassword;
			this.dbURI = dbURI;
			this.dbIPAddress = dbIPAddress;
			this.dbPort = dbPort;
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) 
				throws FormException {

            rtPath = formData.getString("rtPath");
			lsIPAddress = formData.getString("lsIPAddress"); 
			lsPort = formData.getString("lsPort"); 
			dbName = formData.getString("dbName"); 
			dbType = formData.getString("dbType"); 
			dbURI = formData.getString("dbURI"); 
			dbIPAddress = formData.getString("dbIPAddress"); 
			dbPort = formData.getString("dbPort"); 
			dbUsername = formData.getString("dbUsername"); 
			dbPassword = formData.getString("dbPassword"); 

			setDbType(formData.getString("dbType")); 
			save();
			return false;
		}

		public ListBoxModel doFillDbTypeItems(){
			return new ListBoxModel(
					new Option("MySQL", "MySQL", dbType.equals("MySQL")),
					new Option("PostgreSQL", "PostgreSQL", 
							dbType.equals("PostgreSQL"))); 

		}

		/**
		 * If the URI is used, must extract the database type to initialize 
		 * the correct JDBC class driver. 
		 * @return database type 
		 */
		private static String parseType(String uri) { 
			if (uri.toLowerCase().contains("mysql")) { 
				return "mysql"; 
			} else { 
				return "postgresql"; 
			}
		}
				
		public FormValidation doTestExecutablePath(
				@QueryParameter final String rtPath) 
						throws IOException, ServletException {
			//paths must end at executables, OK if paths are empty 
			if((!rtPath.isEmpty() && (rtPath.indexOf("itestrt") == -1))){ 
				return FormValidation.error("RT path does not end"
						+ " at executable"); 
			}
			return FormValidation.ok("Success"); 
		}
				
		public FormValidation doTestLicenseServerConnection(
				@QueryParameter final String lsIPAddress, 
				@QueryParameter final String lsPort) 
						throws IOException, ServletException {
			try {
				//must specify license server 
				if (lsIPAddress.isEmpty()) { 
					return FormValidation.error("Must specify license server"); 
				}

				//test connection 
				try {
					Socket socket = new Socket();
					Integer portNumber = lsPort.isEmpty() ? 
							27000 : Integer.parseInt(lsPort); 

					//InetSocketAddress resolves host name to IP if necessary
					socket.connect(new InetSocketAddress(lsIPAddress, 
							portNumber), 1000);
					
					//if it cannot connect, exception is thrown 
					socket.close();
					return FormValidation.ok("Connected to license server"); 
				} catch (IOException ex) {
					return FormValidation.error("Cannot reach license server"); 
				} 
			} catch (Exception e) {
				e.printStackTrace();
				return FormValidation.error("Client error");
			}
		}

		public FormValidation doTestConnection(
				@QueryParameter final String dbName, 
				@QueryParameter final String dbType, 
				@QueryParameter final String dbURI, 
				@QueryParameter final String dbIPAddress, 
				@QueryParameter final String dbPort, 
				@QueryParameter final String dbUsername,
				@QueryParameter final String dbPassword) 
						throws IOException, ServletException {

			Connection connection = null;
			String dburl = null; 

			if (dbURI.isEmpty()) { 
				if (dbName.isEmpty() || dbType.isEmpty() 
						|| dbIPAddress.isEmpty() ||dbPort.isEmpty() 
						|| dbUsername.isEmpty() || dbPassword.isEmpty()) { 
					return FormValidation.error("Missing required field"); 
				}

				//build the connection URI 
				dburl = "jdbc:" + dbType.toLowerCase() + "://" 
						+ dbIPAddress + ":" + dbPort + "/" + dbName; 
			} else {
				dburl = dbURI; 
			}

			//required for both URI or individual fields 
			if (dbUsername.isEmpty() || dbPassword.isEmpty()) { 
				return FormValidation.error("Please specify username "
						+ "and password"); 
			}

			try { 
				
				//initialize class driver 
				if (dbType.equalsIgnoreCase("MySQL") 
						|| parseType(dbType).equalsIgnoreCase("mysql")) {
					Class.forName("com.mysql.jdbc.Driver");
				}

				if (dbType.equalsIgnoreCase("PostgreSQL") 
						|| parseType(dbType).equalsIgnoreCase("postgresql")) { 
					Class.forName("org.postgresql.Driver");
				}

				connection = DriverManager.getConnection(dburl, dbUsername,
						dbPassword);

                return FormValidation.ok("Success");
            } catch (SQLException e) {
				return FormValidation.error("Please check database "
						+ "credentials"); 
            } catch (ClassNotFoundException e) {
                return FormValidation.error(e.getMessage());
            } finally {
                if (connection != null)
                    try {
                        connection.close();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
				}
			}
		}

	@Override
	protected String getContents() {
		// required, but JavaDoc doesn't mention function 
		return null;
	}

	@Override
	protected String getFileExtension() {
		// required, but JavaDoc doesn't mention function 
		return null;
	}

	@Override
	public String[] buildCommandLine(FilePath script) {
		// required, but JavaDoc doesn't mention function 
		return null;
	}
}
