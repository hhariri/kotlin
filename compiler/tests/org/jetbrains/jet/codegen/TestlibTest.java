/*
 * Copyright 2010-2013 JetBrains s.r.o.
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

package org.jetbrains.jet.codegen;

import com.google.common.base.Predicates;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.UsefulTestCase;
import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import kotlin.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.ConfigurationKind;
import org.jetbrains.jet.JetTestCaseBuilder;
import org.jetbrains.jet.JetTestUtils;
import org.jetbrains.jet.TestJdkKind;
import org.jetbrains.jet.analyzer.AnalyzeExhaust;
import org.jetbrains.jet.cli.common.messages.AnalyzerWithCompilerReport;
import org.jetbrains.jet.cli.common.messages.MessageCollectorPlainTextToStream;
import org.jetbrains.jet.cli.jvm.compiler.JetCoreEnvironment;
import org.jetbrains.jet.codegen.forTestCompile.ForTestCompileRuntime;
import org.jetbrains.jet.codegen.state.GenerationState;
import org.jetbrains.jet.config.CommonConfigurationKeys;
import org.jetbrains.jet.config.CompilerConfiguration;
import org.jetbrains.jet.lang.descriptors.ClassDescriptor;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor;
import org.jetbrains.jet.lang.descriptors.Modality;
import org.jetbrains.jet.lang.psi.JetClass;
import org.jetbrains.jet.lang.psi.JetDeclaration;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.lazy.JvmResolveUtil;
import org.jetbrains.jet.lang.resolve.name.FqNameUnsafe;
import org.jetbrains.jet.utils.UtilsPackage;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.jetbrains.jet.lang.resolve.BindingContext.DECLARATION_TO_DESCRIPTOR;

@SuppressWarnings("JUnitTestCaseWithNoTests")
public class TestlibTest extends UsefulTestCase {
    public static Test suite() {
        return new TestlibTest().buildTestSuite();
    }

    private TestSuite suite;

    private Test buildTestSuite() {
        suite = new TestSuite("stdlib test");

        return new TestSetup(suite) {
            @Override
            protected void setUp() throws Exception {
                TestlibTest.this.setUp();
            }

            @Override
            protected void tearDown() throws Exception {
                TestlibTest.this.tearDown();
            }
        };
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        File junitJar = new File("libraries/lib/junit-4.9.jar");
        assertTrue(junitJar.exists());

        CompilerConfiguration configuration =
                JetTestUtils.compilerConfigurationForTests(ConfigurationKind.ALL, TestJdkKind.FULL_JDK, junitJar);

        configuration.add(CommonConfigurationKeys.SOURCE_ROOTS_KEY, JetTestCaseBuilder.getHomeDirectory() + "/libraries/stdlib/test");
        configuration.add(CommonConfigurationKeys.SOURCE_ROOTS_KEY, JetTestCaseBuilder.getHomeDirectory() + "/libraries/kunit/src");

        final JetCoreEnvironment environment = JetCoreEnvironment.createForTests(getTestRootDisposable(), configuration);

        AnalyzerWithCompilerReport analyzer = new AnalyzerWithCompilerReport(MessageCollectorPlainTextToStream.PLAIN_TEXT_TO_SYSTEM_ERR);
        analyzer.analyzeAndReport(new Function0<AnalyzeExhaust>() {
            @Override
            public AnalyzeExhaust invoke() {
                return JvmResolveUtil.analyzeFilesWithJavaIntegration(environment.getProject(), environment.getSourceFiles(),
                                                                      Predicates.<PsiFile>alwaysTrue());
            }
        }, environment.getSourceFiles());

        AnalyzeExhaust exhaust = analyzer.getAnalyzeExhaust();
        assert exhaust != null : "Not analyzed";
        exhaust.throwIfError();

        final StdLibTests stdLibTests = new StdLibTests(environment, analyzer);

        int totalTestMethods = 0;
        for (JetFile file : environment.getSourceFiles()) {
            for (JetDeclaration declaration : file.getDeclarations()) {
                if (!(declaration instanceof JetClass)) continue;

                DeclarationDescriptor declarationDescriptor = exhaust.getBindingContext().get(DECLARATION_TO_DESCRIPTOR, declaration);
                if (!(declarationDescriptor instanceof ClassDescriptor)) continue;
                final ClassDescriptor descriptor = (ClassDescriptor) declarationDescriptor;

                if (descriptor.getModality() == Modality.ABSTRACT) continue;

                boolean isTest = false;
                for (ClassDescriptor superClass : DescriptorUtils.getAllSuperClasses(descriptor)) {
                    isTest |= DescriptorUtils.getFqName(superClass).asString().equals("junit.framework.Test");
                }
                if (!isTest) continue;

                List<String> testMethods = new ArrayList<String>();
                for (DeclarationDescriptor member : descriptor.getDefaultType().getMemberScope().getAllDescriptors()) {
                    if (member instanceof FunctionDescriptor) {
                        String name = member.getName().asString();
                        if (name.startsWith("test")) {
                            testMethods.add(name);
                        }
                    }
                }
                if (testMethods.isEmpty()) continue;

                totalTestMethods += testMethods.size();

                for (final String testMethod : testMethods) {
                    suite.addTest(new TestCase() {
                        @Override
                        public void run(TestResult result) {
                            TestCase testCase = stdLibTests.loadTestCase(DescriptorUtils.getFqName(descriptor));
                            testCase.setName(testMethod);
                            suite.runTest(testCase, result);
                        }
                    });
                }
            }
        }

        // This check can fail if many tests are deleted from stdlib
        if (totalTestMethods < 40) {
            fail("Too few tests were found in stdlib: " + totalTestMethods);
        }
    }

    private static class StdLibTests {
        private final JetCoreEnvironment environment;
        private final AnalyzerWithCompilerReport analyzer;

        private GeneratedClassLoader classLoader;

        public StdLibTests(@NotNull JetCoreEnvironment environment, @NotNull AnalyzerWithCompilerReport analyzer) {
            this.environment = environment;
            this.analyzer = analyzer;
        }

        private void compileToClassFiles() throws Exception {
            if (analyzer.hasErrors()) {
                throw new IllegalStateException("There were compilation errors");
            }

            //noinspection ConstantConditions
            GenerationState state =
                    GenerationUtils.compileFilesGetGenerationState(environment.getProject(), analyzer.getAnalyzeExhaust(),
                                                                   environment.getSourceFiles());

            classLoader = new GeneratedClassLoader(state.getFactory(), new URLClassLoader(
                    new URL[] {ForTestCompileRuntime.runtimeJarForTests().toURI().toURL()}, null)
            ) {
                @Override
                public Class<?> loadClass(@NotNull String name) throws ClassNotFoundException {
                    if (name.startsWith("junit.") || name.startsWith("org.junit.")) {
                        //In other way we don't find any test cause will have two different TestCase classes!
                        return TestlibTest.class.getClassLoader().loadClass(name);
                    }
                    return super.loadClass(name);
                }
            };
        }

        @NotNull
        public TestCase loadTestCase(@NotNull FqNameUnsafe fqName) {
            try {
                if (classLoader == null) {
                    compileToClassFiles();
                }
                Class<?> aClass = classLoader.loadClass(fqName.asString());
                return (TestCase) aClass.newInstance();
            }
            catch (Exception e) {
                throw UtilsPackage.rethrow(e);
            }
        }
    }
}
