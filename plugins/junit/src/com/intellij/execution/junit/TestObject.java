/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.execution.junit;

import com.intellij.execution.*;
import com.intellij.execution.configurations.*;
import com.intellij.execution.junit2.TestProxy;
import com.intellij.execution.junit2.segments.DeferredActionsQueue;
import com.intellij.execution.junit2.segments.DeferredActionsQueueImpl;
import com.intellij.execution.junit2.segments.DispatchListener;
import com.intellij.execution.junit2.segments.Extractor;
import com.intellij.execution.junit2.ui.JUnitTreeConsoleView;
import com.intellij.execution.junit2.ui.TestsPacketsReceiver;
import com.intellij.execution.junit2.ui.actions.RerunFailedTestsAction;
import com.intellij.execution.junit2.ui.model.CompletionEvent;
import com.intellij.execution.junit2.ui.model.JUnitRunningModel;
import com.intellij.execution.junit2.ui.model.RootTestInfo;
import com.intellij.execution.junit2.ui.properties.JUnitConsoleProperties;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.testframework.*;
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.util.JavaParametersUtil;
import com.intellij.execution.util.ProgramParametersUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.JdkUtil;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.ex.JavaSdkUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Getter;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.util.text.StringUtilRt;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopesCore;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.rt.execution.CommandLineWrapper;
import com.intellij.rt.execution.junit.IDEAJUnitListener;
import com.intellij.rt.execution.junit.JUnitStarter;
import com.intellij.rt.execution.junit.RepeatCount;
import com.intellij.util.Function;
import com.intellij.util.PathUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.serialization.PathMacroUtil;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

public abstract class TestObject extends JavaTestFrameworkRunnableState<JUnitConfiguration> {
  protected static final Logger LOG = Logger.getInstance(TestObject.class);

  private static final String MESSAGE = ExecutionBundle.message("configuration.not.speficied.message");
  @NonNls private static final String JUNIT_TEST_FRAMEWORK_NAME = "JUnit";

  private final JUnitConfiguration myConfiguration;
  protected File myWorkingDirsFile = null;
  public File myListenersFile;

  public static TestObject fromString(final String id,
                                      final JUnitConfiguration configuration,
                                      @NotNull ExecutionEnvironment environment) {
    if (JUnitConfiguration.TEST_METHOD.equals(id)) {
      return new TestMethod(configuration, environment);
    }
    if (JUnitConfiguration.TEST_CLASS.equals(id)) {
      return new TestClass(configuration, environment);
    }
    if (JUnitConfiguration.TEST_PACKAGE.equals(id)){
      return new TestPackage(configuration, environment);
    }
    if (JUnitConfiguration.TEST_DIRECTORY.equals(id)) {
      return new TestDirectory(configuration, environment);
    }
    if (JUnitConfiguration.TEST_CATEGORY.equals(id)) {
      return new TestCategory(configuration, environment);
    }
    if (JUnitConfiguration.TEST_PATTERN.equals(id)) {
      return new TestsPattern(configuration, environment);
    }
    assert false : MESSAGE + id;
    return null;
  }

  public Module[] getModulesToCompile() {
    final SourceScope sourceScope = getSourceScope();
    return sourceScope != null ? sourceScope.getModulesToCompile() : Module.EMPTY_ARRAY;
  }

  protected TestObject(JUnitConfiguration configuration, ExecutionEnvironment environment) {
    super(environment);
    myConfiguration = configuration;
  }

  public abstract String suggestActionName();

  public abstract RefactoringElementListener getListener(PsiElement element, JUnitConfiguration configuration);

  public abstract boolean isConfiguredByElement(JUnitConfiguration configuration,
                                                PsiClass testClass,
                                                PsiMethod testMethod,
                                                PsiPackage testPackage,
                                                PsiDirectory testDir);

  protected void configureModule(final JavaParameters parameters, final RunConfigurationModule configurationModule, final String mainClassName)
    throws CantRunException {
    int classPathType = JavaParametersUtil.getClasspathType(configurationModule, mainClassName, true);
    JavaParametersUtil.configureModule(configurationModule, parameters, classPathType,
                                       getConfiguration().isAlternativeJrePathEnabled() ? getConfiguration().getAlternativeJrePath() : null);
  }

  public void checkConfiguration() throws RuntimeConfigurationException{
    JavaParametersUtil.checkAlternativeJRE(getConfiguration());
    ProgramParametersUtil.checkWorkingDirectoryExist(getConfiguration(), getConfiguration().getProject(),
                                                     getConfiguration().getConfigurationModule().getModule());
  }

  public SourceScope getSourceScope() {
    return SourceScope.modulesWithDependencies(getConfiguration().getModules());
  }

  protected void initialize(JavaParameters javaParameters) throws ExecutionException {
    String parameters = getConfiguration().getProgramParameters();
    javaParameters.setMainClass(JUnitConfiguration.JUNIT_START_CLASS);
    final Module module = getConfiguration().getConfigurationModule().getModule();
    if (javaParameters.getJdk() == null){
      javaParameters.setJdk(module != null
                            ? ModuleRootManager.getInstance(module).getSdk()
                            : ProjectRootManager.getInstance(getConfiguration().getProject()).getProjectSdk());
    }

    javaParameters.getClassPath().add(PathUtil.getJarPathForClass(JUnitStarter.class));
    javaParameters.getProgramParametersList().add(JUnitStarter.IDE_VERSION + JUnitStarter.VERSION);
    if (!StringUtil.isEmptyOrSpaces(parameters)) {
      javaParameters.getProgramParametersList().add("@name" + parameters);
    }

    final StringBuilder buf = new StringBuilder();
    collectListeners(javaParameters, buf, IDEAJUnitListener.EP_NAME, "\n");
    if (buf.length() > 0) {
      try {
        myListenersFile = FileUtil.createTempFile("junit_listeners_", "");
        myListenersFile.deleteOnExit();
        javaParameters.getProgramParametersList().add("@@" + myListenersFile.getPath());
        FileUtil.writeToFile(myListenersFile, buf.toString().getBytes(CharsetToolkit.UTF8_CHARSET));
      }
      catch (IOException e) {
        LOG.error(e);
      }
    }
  }

  private static void configureAdditionalClasspath(JavaParameters javaParameters) {
    javaParameters.getClassPath().add(JavaSdkUtil.getIdeaRtJarPath());
    javaParameters.getClassPath().add(PathUtil.getJarPathForClass(JUnitStarter.class));
  }

  @Override
  protected JavaParameters createJavaParameters() throws ExecutionException {
    JavaParameters javaParameters = super.createJavaParameters();
    initialize(javaParameters);
    return javaParameters;
  }

  @NotNull
  @Override
  public ExecutionResult execute(@NotNull final Executor executor, @NotNull final ProgramRunner runner) throws ExecutionException {
    final boolean smRunner = Registry.is("junit_sm_runner");
    if (smRunner) {
      return startSMRunner(executor, createHandler(executor), getConfiguration(), getEnvironment());
    }
    final JUnitProcessHandler handler = createHandler(executor);
    final RunnerSettings runnerSettings = getRunnerSettings();
    JavaRunConfigurationExtensionManager.getInstance().attachExtensionsToProcess(getConfiguration(), handler, runnerSettings);
    final TestProxy unboundOutputRoot = new TestProxy(new RootTestInfo());
    final JUnitConsoleProperties consoleProperties = new JUnitConsoleProperties(getConfiguration(), executor);
    final JUnitTreeConsoleView consoleView = new JUnitTreeConsoleView(consoleProperties, getEnvironment(), unboundOutputRoot);
    Disposer.register(getConfiguration().getProject(), consoleView);
    consoleView.initUI();
    consoleView.attachToProcess(handler);
    unboundOutputRoot.setPrinter(consoleView.getPrinter());
    Disposer.register(consoleView, unboundOutputRoot);
    final TestsPacketsReceiver packetsReceiver = new TestsPacketsReceiver(consoleView, unboundOutputRoot) {
      @Override
      public synchronized void notifyStart(TestProxy root) {
        if (!isRunning()) return;
        super.notifyStart(root);
        unboundOutputRoot.addChild(root);
        if (getConfiguration().isSaveOutputToFile()) {
          unboundOutputRoot.setOutputFilePath(getConfiguration().getOutputFilePath());
        }
        final JUnitRunningModel model = getModel();
        if (model != null) {
          handler.getOut().setDispatchListener(model.getNotifier());
          Disposer.register(model, new Disposable() {
            @Override
            public void dispose() {
              handler.getOut().setDispatchListener(DispatchListener.DEAF);
            }
          });
          consoleView.attachToModel(model);
        }
      }
    };
    Disposer.register(consoleView, packetsReceiver);

    final DeferredActionsQueue queue = new DeferredActionsQueueImpl();
    handler.getOut().setPacketDispatcher(packetsReceiver, queue);
    handler.getErr().setPacketDispatcher(packetsReceiver, queue);

    handler.addProcessListener(new ProcessAdapter() {
      private boolean myStarted = false;

      @Override
      public void startNotified(ProcessEvent event) {
        myStarted = true;
      }

      @Override
      public void processTerminated(ProcessEvent event) {
        handler.removeProcessListener(this);
        if (myTempFile != null) {
          FileUtil.delete(myTempFile);
        }
        if (myListenersFile != null) {
          FileUtil.delete(myListenersFile);
        }
        final Runnable runnable = new Runnable() {
          @Override
          public void run() {
            unboundOutputRoot.flush();
            packetsReceiver.checkTerminated();
            final JUnitRunningModel model = packetsReceiver.getModel();
            notifyByBalloon(model, myStarted, consoleProperties);
          }
        };
        handler.getOut().addRequest(runnable, queue);
      }

      @Override
      public void onTextAvailable(final ProcessEvent event, final Key outputType) {
        final String text = event.getText();
        final ConsoleViewContentType consoleViewType = ConsoleViewContentType.getConsoleViewType(outputType);
        final Printable printable = new Printable() {
          @Override
          public void printOn(final Printer printer) {
            printer.print(text, consoleViewType);
          }
        };
        final Extractor extractor;
        if (consoleViewType == ConsoleViewContentType.ERROR_OUTPUT ||
            consoleViewType == ConsoleViewContentType.SYSTEM_OUTPUT) {
          extractor = handler.getErr();
        }
        else {
          extractor = handler.getOut();
        }
        extractor.getEventsDispatcher().processOutput(printable);
      }
    });

    final RerunFailedTestsAction rerunFailedTestsAction = new RerunFailedTestsAction(consoleView, consoleProperties);
    rerunFailedTestsAction.setModelProvider(new Getter<TestFrameworkRunningModel>() {
      @Override
      public TestFrameworkRunningModel get() {
        return packetsReceiver.getModel();
      }
    });

    final DefaultExecutionResult result = new DefaultExecutionResult(consoleView, handler);
    result.setRestartActions(rerunFailedTestsAction);
    return result;
  }

  protected void notifyByBalloon(JUnitRunningModel model, boolean started, JUnitConsoleProperties consoleProperties) {
    String comment;
    if (model != null) {
      final CompletionEvent done = model.getProgress().getDone();
      comment = done != null ? done.getComment() : null;
    }
    else {
      comment = null;
    }
    TestsUIUtil.notifyByBalloon(consoleProperties.getProject(), started, model != null ? model.getRoot() : null, consoleProperties, comment);
  }

  protected JUnitProcessHandler createHandler(Executor executor) throws ExecutionException {
    appendForkInfo(executor);
    final String repeatMode = getConfiguration().getRepeatMode();
    if (!RepeatCount.ONCE.equals(repeatMode)) {
      final int repeatCount = getConfiguration().getRepeatCount();
      final String countString = RepeatCount.N.equals(repeatMode) && repeatCount > 0
                                 ? RepeatCount.getCountString(repeatCount) 
                                 : repeatMode;
      getJavaParameters().getProgramParametersList().add(countString);
    }
    return JUnitProcessHandler.runCommandLine(createCommandLine());
  }

  private boolean forkPerModule() {
    final String workingDirectory = getConfiguration().getWorkingDirectory();
    return JUnitConfiguration.TEST_PACKAGE.equals(getConfiguration().getPersistentData().TEST_OBJECT) &&
           getConfiguration().getPersistentData().getScope() != TestSearchScope.SINGLE_MODULE &&
           ("$" + PathMacroUtil.MODULE_DIR_MACRO_NAME + "$").equals(workingDirectory) &&
           spansMultipleModules();
  }

  private boolean spansMultipleModules() {
    final String qualifiedName = getConfiguration().getPackage();
    if (qualifiedName != null) {
      final Project project = getConfiguration().getProject();
      final PsiPackage aPackage = JavaPsiFacade.getInstance(project).findPackage(qualifiedName);
      if (aPackage != null) {
        final TestSearchScope scope = getConfiguration().getPersistentData().getScope();
        if (scope != null) {
          final SourceScope sourceScope = scope.getSourceScope(getConfiguration());
          if (sourceScope != null) {
            final GlobalSearchScope configurationSearchScope = GlobalSearchScopesCore.projectTestScope(project).intersectWith(
              sourceScope.getGlobalSearchScope());
            final PsiDirectory[] directories = aPackage.getDirectories(configurationSearchScope);
            return directories.length > 1;
          }
        }
      }
    }
    return false;
  }

  private void appendForkInfo(Executor executor) throws ExecutionException {
    final String forkMode = getConfiguration().getForkMode();
    if (Comparing.strEqual(forkMode, "none")) {
      if (forkPerModule()) {
        if (getRunnerSettings() != null) {
          final String actionName = UIUtil.removeMnemonic(executor.getStartActionText());
          throw new CantRunException("'" + actionName + "' is disabled when per-module working directory is configured.<br/>" +
                                     "Please specify single working directory, or change test scope to single module.");
        }
      } else {
        return;
      }
    } else if (getRunnerSettings() != null) {
      final String actionName = executor.getActionName();
      throw new CantRunException(actionName + " is disabled in fork mode.<br/>Please change fork mode to &lt;none&gt; to " + actionName.toLowerCase(Locale.ENGLISH) + ".");
    }

    final JavaParameters javaParameters = getJavaParameters();
    final Sdk jdk = javaParameters.getJdk();
    if (jdk == null) {
      throw new ExecutionException(ExecutionBundle.message("run.configuration.error.no.jdk.specified"));
    }

    try {
      final File tempFile = FileUtil.createTempFile("command.line", "", true);
      final PrintWriter writer = new PrintWriter(tempFile, CharsetToolkit.UTF8);
      try {
        if (JdkUtil.useDynamicClasspath(getConfiguration().getProject())) {
          String classpath = PathUtil.getJarPathForClass(CommandLineWrapper.class);
          final String utilRtPath = PathUtil.getJarPathForClass(StringUtilRt.class);
          if (!classpath.equals(utilRtPath)) {
            classpath += File.pathSeparator + utilRtPath;
          }
          writer.println(classpath);
        }
        else {
          writer.println("");
        }

        writer.println(((JavaSdkType)jdk.getSdkType()).getVMExecutablePath(jdk));
        for (String vmParameter : javaParameters.getVMParametersList().getList()) {
          writer.println(vmParameter);
        }
      }
      finally {
        writer.close();
      }

      getJavaParameters().getProgramParametersList().add("@@@" + forkMode + ',' + tempFile.getAbsolutePath());
    }
    catch (Exception e) {
      LOG.error(e);
    }
  }

  protected <T> void addClassesListToJavaParameters(Collection<? extends T> elements, Function<T, String> nameFunction, String packageName,
                                                    boolean createTempFile, JavaParameters javaParameters) throws CantRunException {
    try {
      if (createTempFile) {
        createTempFiles(javaParameters);
      }

      final Map<Module, List<String>> perModule = forkPerModule() ? new TreeMap<Module, List<String>>(new Comparator<Module>() {
        @Override
        public int compare(Module o1, Module o2) {
          return StringUtil.compare(o1.getName(), o2.getName(), true);
        }
      }) : null;

      final List<String> testNames = new ArrayList<String>();

      for (final T element : elements) {
        final String name = nameFunction.fun(element);
        if (name == null) {
          LOG.error("invalid element " + element);
          return;
        }

        if (perModule != null && element instanceof PsiElement) {
          final Module module = ModuleUtilCore.findModuleForPsiElement((PsiElement)element);
          if (module != null) {
            List<String> list = perModule.get(module);
            if (list == null) {
              list = new ArrayList<String>();
              perModule.put(module, list);
            }
            list.add(name);
          }
        }
        else {
          testNames.add(name);
        }
      }
      final JUnitConfiguration.Data data = getConfiguration().getPersistentData();
      if (perModule != null) {
        for (List<String> perModuleClasses : perModule.values()) {
          Collections.sort(perModuleClasses);
          testNames.addAll(perModuleClasses);
        }
      }
      else if (JUnitConfiguration.TEST_PACKAGE.equals(data.TEST_OBJECT)) {
        Collections.sort(testNames); //sort tests in FQN order
      }

      final String category = JUnitConfiguration.TEST_CATEGORY.equals(data.TEST_OBJECT) ? data.getCategory() : "";
      JUnitStarter.printClassesList(testNames, packageName, category, myTempFile);

      if (perModule != null && perModule.size() > 1) {
        final String classpath = getConfiguration().getPersistentData().getScope() == TestSearchScope.WHOLE_PROJECT
                                 ? null : javaParameters.getClassPath().getPathsString();

        final PrintWriter wWriter = new PrintWriter(myWorkingDirsFile, CharsetToolkit.UTF8);
        try {
          wWriter.println(packageName);
          for (Module module : perModule.keySet()) {
            final String moduleDir = PathMacroUtil.getModuleDir(module.getModuleFilePath());
            wWriter.println(moduleDir);

            if (classpath == null) {
              final JavaParameters parameters = new JavaParameters();
              configureAdditionalClasspath(parameters);
              JavaParametersUtil.configureModule(module, parameters, JavaParameters.JDK_AND_CLASSES_AND_TESTS,
                                                 getConfiguration().isAlternativeJrePathEnabled() ? getConfiguration()
                                                   .getAlternativeJrePath() : null);
              wWriter.println(parameters.getClassPath().getPathsString());
            } else {
              wWriter.println(classpath);
            }

            final List<String> classNames = perModule.get(module);
            wWriter.println(classNames.size());
            for (String className : classNames) {
              wWriter.println(className);
            }
          }
        } finally {
          wWriter.close();
        }
      }
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  protected void createTempFiles(JavaParameters javaParameters) {
    super.createTempFiles(javaParameters);
    try {
      myWorkingDirsFile = FileUtil.createTempFile("idea_working_dirs_junit", ".tmp");
      myWorkingDirsFile.deleteOnExit();
      javaParameters.getProgramParametersList().add("@w@" + myWorkingDirsFile.getAbsolutePath());
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  public void clear() {
  }

  @NotNull
  protected String getFrameworkName() {
    return JUNIT_TEST_FRAMEWORK_NAME;
  }

  @NotNull
  protected AbstractRerunFailedTestsAction createRerunFailedTestsAction(TestConsoleProperties testConsoleProperties,
                                                                        ConsoleView consoleView) {
    return new RerunFailedTestsAction(consoleView, testConsoleProperties);
  }

  @NotNull
  protected String getFrameworkId() {
    return "junit";
  }

  protected void passTempFile(ParametersList parametersList, String tempFilePath) {
    parametersList.add("@" + tempFilePath);
  }

  @NotNull
  public JUnitConfiguration getConfiguration() {
    return myConfiguration;
  }
}
