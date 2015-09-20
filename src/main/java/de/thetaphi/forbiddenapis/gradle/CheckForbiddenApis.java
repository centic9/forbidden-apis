package de.thetaphi.forbiddenapis.gradle;

/*
 * (C) Copyright Uwe Schindler (Generics Policeman) and others.
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

import static de.thetaphi.forbiddenapis.Checker.Option.*;
import groovy.lang.Closure;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.RetentionPolicy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.resources.ResourceException;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.ParallelizableTask;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.VerificationTask;
import org.gradle.api.tasks.util.PatternFilterable;
import org.gradle.api.tasks.util.PatternSet;

import de.thetaphi.forbiddenapis.Checker;
import de.thetaphi.forbiddenapis.ForbiddenApiException;
import de.thetaphi.forbiddenapis.Logger;
import de.thetaphi.forbiddenapis.ParseException;

/**
 * ForbiddenApis Gradle Task (requires at least Gradle 2.3)
 * @since 2.0
 */
@ParallelizableTask
public class CheckForbiddenApis extends DefaultTask implements PatternFilterable,VerificationTask {
  
  private final CheckForbiddenApisExtension data = new CheckForbiddenApisExtension();
  private final PatternSet patternSet = new PatternSet().include("**/*.class");
  private File classesDir;
  private FileCollection classpath;
  private String targetCompatibility;
  
  /**
   * Directory with the class files to check.
   */
  @OutputDirectory
  public File getClassesDir() {
    return classesDir;
  }

  /** @see #getClassesDir */
  public void setClassesDir(File classesDir) {
    this.classesDir = classesDir;
  }

  /**
   * A {@link FileCollection} containing all files, which contain signatures and comments for forbidden API calls.
   * The signatures are resolved against the compile classpath.
   */
  @InputFiles
  public FileCollection getClasspath() {
    return classpath;
  }

  /** @see #getClasspath */
  public void setClasspath(FileCollection classpath) {
    this.classpath = classpath;
  }

  /**
   * A {@link FileCollection} used to configure the classpath.
   */
  @InputFiles
  @Optional
  public FileCollection getSignaturesFiles() {
    return data.signaturesFiles;
  }

  /** @see #getSignaturesFiles */
  public void setSignaturesFiles(FileCollection signaturesFiles) {
    data.signaturesFiles = signaturesFiles;
  }

  /**
   * Gives multiple API signatures that are joined with newlines and
   * parsed like a single {@link #signaturesFiles}.
   * The signatures are resolved against the compile classpath.
   */
  @Input
  @Optional
  public List<String> getSignatures() {
    return data.signatures;
  }

  /** @see #getSignatures */
  public void setSignatures(List<String> signatures) {
    data.signatures.clear();
    data.signatures.addAll(signatures);
  }

  /**
   * Specifies <a href="bundled-signatures.html">built-in signatures</a> files (e.g., deprecated APIs for specific Java versions,
   * unsafe method calls using default locale, default charset,...)
   */
  @Input
  @Optional
  public List<String> getBundledSignatures() {
    return data.bundledSignatures;
  }

  /** @see #getBundledSignatures */
  public void setBundledSignatures(List<String> bundledSignatures) {
    data.bundledSignatures.clear();
    data.bundledSignatures.addAll(bundledSignatures);
  }

  /**
   * Forbids calls to classes from the internal java runtime (like sun.misc.Unsafe)
   */
  @Input
  public boolean getInternalRuntimeForbidden() {
    return data.internalRuntimeForbidden;
  }

  /** @see #getInternalRuntimeForbidden */
  public void setInternalRuntimeForbidden(boolean internalRuntimeForbidden) {
    data.internalRuntimeForbidden = internalRuntimeForbidden;
  }

  /**
   * Fail the build, if the bundled ASM library cannot read the class file format
   * of the runtime library or the runtime library cannot be discovered.
   */
  @Input
  public boolean getFailOnUnsupportedJava() {
    return data.failOnUnsupportedJava;
  }

  /** @see #getFailOnUnsupportedJava */
  public void setFailOnUnsupportedJava(boolean failOnUnsupportedJava) {
    data.failOnUnsupportedJava = failOnUnsupportedJava;
  }

  /**
   * Fail the build, if a class referenced in the scanned code is missing. This requires
   * that you pass the whole classpath including all dependencies to this task
   * (Gradle does this by default).
   */
  @Input
  public boolean getFailOnMissingClasses() {
    return data.failOnMissingClasses;
  }

  /** @see #getFailOnMissingClasses */
  public void setFailOnMissingClasses(boolean failOnMissingClasses) {
    data.failOnMissingClasses = failOnMissingClasses;
  }

  /**
   * Fail the build if a signature is not resolving. If this parameter is set to
   * to false, then such signatures are silently ignored. This is useful in multi-module Maven
   * projects where only some modules have the dependency to which the signature file(s) apply.
   */
  @Input
  public boolean getFailOnUnresolvableSignatures() {
    return data.failOnUnresolvableSignatures;
  }

  /** @see #getFailOnUnresolvableSignatures */
  public void setFailOnUnresolvableSignatures(boolean failOnUnresolvableSignatures) {
    data.failOnUnresolvableSignatures = failOnUnresolvableSignatures;
  }

  /**
   * @{inheritDoc}
   * <p>
   * This setting is to conform with {@link VerificationTask} interface.
   * Other ForbiddenApis implementations use another name: {@code failOnViolation}
   * Default is {@code false}.
   */
  @Override
  public boolean getIgnoreFailures() {
    return data.ignoreFailures;
  }

  @Override
  public void setIgnoreFailures(boolean ignoreFailures) {
    data.ignoreFailures = ignoreFailures;
  }

  /**
   * Disable the internal JVM classloading cache when getting bytecode from
   * the classpath. This setting slows down checks, but <em>may</em> work around
   * issues with other plugin, that do not close their class loaders.
   * If you get {@code FileNotFoundException}s related to non-existent JAR entries
   * you can try to work around using this setting.
   * The default is {@code false}.
   */
  @Input
  public boolean getDisableClassloadingCache() {
    return data.disableClassloadingCache;
  }

  /** @see #getDisableClassloadingCache */
  public void setDisableClassloadingCache(boolean disableClassloadingCache) {
    data.disableClassloadingCache = disableClassloadingCache;
  }

  /**
   * List of a custom Java annotations (full class names) that are used in the checked
   * code to suppress errors. Those annotations must have at least
   * {@link RetentionPolicy#CLASS}. They can be applied to classes, their methods,
   * or fields. By default, {@code @de.thetaphi.forbiddenapis.SuppressForbidden}
   * can always be used, but needs the {@code forbidden-apis.jar} file in classpath
   * of compiled project, which may not be wanted.
   * Instead of a full class name, a glob pattern may be used (e.g.,
   * {@code **.SuppressForbidden}).
   */
  @Input
  @Optional
  public List<String> getSuppressAnnotations() {
    return data.suppressAnnotations;
  }

  /** @see #getSuppressAnnotations */
  public void setSuppressAnnotations(List<String> suppressAnnotations) {
    data.suppressAnnotations.clear();
    data.suppressAnnotations.addAll(suppressAnnotations);
  }
  
  /**
   * The default compiler target version used to expand references to bundled JDK signatures.
   * E.g., if you use "jdk-deprecated", it will expand to this version.
   * This setting should be identical to the target version used in the compiler task.
   * Defaults to {@code project.targetCompatibility}.
   */
  @Input
  @Optional
  public String getTargetCompatibility() {
    return targetCompatibility;
  }

  /** @see #getTargetCompatibility */
  public void setTargetCompatibility(String targetCompatibility) {
    this.targetCompatibility = targetCompatibility;
  }
  
  // PatternFilterable implementation:
  
  /**
   * {@inheritDoc}
   * <p>
   * Set of patterns matching all class files to be parsed from the classesDirectory.
   * Can be changed to e.g. exclude several files (using excludes).
   * The default is a single include with pattern '**&#47;*.class'
   */
  @Override
  @Input
  public Set<String> getIncludes() {
    return getPatternSet().getIncludes();
  }

  @Override
  public CheckForbiddenApis setIncludes(Iterable<String> includes) {
    getPatternSet().setIncludes(includes);
    return this;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Set of patterns matching class files to be excluded from checking.
   */
  @Override
  @Input
  public Set<String> getExcludes() {
    return getPatternSet().getExcludes();
  }

  @Override
  public CheckForbiddenApis setExcludes(Iterable<String> excludes) {
    getPatternSet().setExcludes(excludes);
    return this;
  }

  @Override
  public CheckForbiddenApis exclude(String... arg0) {
    getPatternSet().exclude(arg0);
    return this;
  }

  @Override
  public CheckForbiddenApis exclude(Iterable<String> arg0) {
    getPatternSet().exclude(arg0);
    return this;
  }

  @Override
  public CheckForbiddenApis exclude(Spec<FileTreeElement> arg0) {
    getPatternSet().exclude(arg0);
    return this;
  }

  @Override
  public CheckForbiddenApis exclude(@SuppressWarnings("rawtypes") Closure arg0) {
    getPatternSet().exclude(arg0);
    return this;
  }

  @Override
  public CheckForbiddenApis include(String... arg0) {
    getPatternSet().include(arg0);
    return this;
  }

  @Override
  public CheckForbiddenApis include(Iterable<String> arg0) {
    getPatternSet().include(arg0);
    return this;
  }

  @Override
  public CheckForbiddenApis include(Spec<FileTreeElement> arg0) {
    getPatternSet().include(arg0);
    return this;
  }

  @Override
  public CheckForbiddenApis include(@SuppressWarnings("rawtypes") Closure arg0) {
    getPatternSet().include(arg0);
    return this;
  }

  public PatternSet getPatternSet() {
    return patternSet;
  }
  
  public void setPatternSet(PatternSet patternSet) {
    patternSet.copyFrom(patternSet);
  }

  /** Returns the classes to check. */
  @InputFiles
  @SkipWhenEmpty
  public FileTree getClassFiles() {
    return getProject().files(getClassesDir()).getAsFileTree().matching(getPatternSet());
  }

  @TaskAction
  public void checkForbidden() throws ForbiddenApiException {
    final File classesDir = getClassesDir();
    final FileCollection classpath = getClasspath();
    if (classesDir == null || classpath == null) {
      throw new InvalidUserDataException("Missing 'classesDir' or 'classpath' property.");
    }
    
    final Logger log = new Logger() {
      @Override
      public void error(String msg) {
        getLogger().error(msg);
      }
      
      @Override
      public void warn(String msg) {
        getLogger().warn(msg);
      }
      
      @Override
      public void info(String msg) {
        getLogger().info(msg);
      }
    };
    
    final Collection<File> cpElements = classpath.getFiles();
    final URL[] urls = new URL[cpElements.size() + 1];
    try {
      int i = 0;
      for (final File cpElement : cpElements) {
        urls[i++] = cpElement.toURI().toURL();
      }
      urls[i++] = classesDir.toURI().toURL();
      assert i == urls.length;
    } catch (MalformedURLException mfue) {
      throw new InvalidUserDataException("Failed to build classpath URLs.", mfue);
    }

    URLClassLoader urlLoader = null;
    final ClassLoader loader = (urls.length > 0) ?
      (urlLoader = URLClassLoader.newInstance(urls, ClassLoader.getSystemClassLoader())) :
      ClassLoader.getSystemClassLoader();
    
    try {
      final EnumSet<Checker.Option> options = EnumSet.noneOf(Checker.Option.class);
      if (getInternalRuntimeForbidden()) options.add(INTERNAL_RUNTIME_FORBIDDEN);
      if (getFailOnMissingClasses()) options.add(FAIL_ON_MISSING_CLASSES);
      if (!getIgnoreFailures()) options.add(FAIL_ON_VIOLATION);
      if (getFailOnUnresolvableSignatures()) options.add(FAIL_ON_UNRESOLVABLE_SIGNATURES);
      if (getDisableClassloadingCache()) options.add(DISABLE_CLASSLOADING_CACHE);
      final Checker checker = new Checker(log, loader, options);
      
      if (!checker.isSupportedJDK) {
        final String msg = String.format(Locale.ENGLISH, 
          "Your Java runtime (%s %s) is not supported by the forbiddenapis plugin. Please run the checks with a supported JDK!",
          System.getProperty("java.runtime.name"), System.getProperty("java.runtime.version"));
        if (getFailOnUnsupportedJava()) {
          throw new GradleException(msg);
        } else {
          log.warn(msg);
          return;
        }
      }
      
      final List<String> suppressAnnotations = getSuppressAnnotations();
      if (suppressAnnotations != null) {
        for (String a : suppressAnnotations) {
          checker.addSuppressAnnotation(a);
        }
      }
      
      try {
        final List<String> signatures = getSignatures();
        if (signatures != null && !signatures.isEmpty()) {
          log.info("Reading inline API signatures...");
          final StringBuilder sb = new StringBuilder();
          for (String line : signatures) {
            sb.append(line).append('\n');
          }
          checker.parseSignaturesString(sb.toString());
        }
        final List<String> bundledSignatures = getBundledSignatures();
        if (bundledSignatures != null) {
          final String bundledSigsJavaVersion = getTargetCompatibility();
          if (bundledSigsJavaVersion == null) {
            log.warn("The 'targetCompatibility' project or task property is missing. " +
              "Trying to read bundled JDK signatures without compiler target. " +
              "You have to explicitely specify the version in the resource name.");
          }
          for (String bs : bundledSignatures) {
            log.info("Reading bundled API signatures: " + bs);
            checker.parseBundledSignatures(bs, bundledSigsJavaVersion);
          }
        }
        final FileCollection signaturesFiles = getSignaturesFiles();
        if (signaturesFiles != null) for (final File f : signaturesFiles) {
          log.info("Reading API signatures: " + f);
          checker.parseSignaturesFile(f);
        }
      } catch (IOException ioe) {
        throw new ResourceException("IO problem while reading files with API signatures.", ioe);
      } catch (ParseException pe) {
        throw new InvalidUserDataException("Parsing signatures failed: " + pe.getMessage(), pe);
      }

      if (checker.hasNoSignatures()) {
        if (options.contains(FAIL_ON_UNRESOLVABLE_SIGNATURES)) {
          throw new InvalidUserDataException("No API signatures found; use parameters 'signatures', 'bundledSignatures', and/or 'signaturesFiles' to define those!");
        } else {
          log.info("Skipping execution because no API signatures are available.");
          return;
        }
      }

      log.info("Loading classes to check...");
      try {
        for (File f : getClassFiles()) {
          checker.addClassToCheck(f);
        }
      } catch (IOException ioe) {
        throw new ResourceException("Failed to load one of the given class files.", ioe);
      }

      log.info("Scanning for API signatures and dependencies...");
      checker.run();
    } finally {
      // Java 7 supports closing URLClassLoader, so check for Closeable interface:
      if (urlLoader instanceof Closeable) try {
        ((Closeable) urlLoader).close();
      } catch (IOException ioe) {
        // ignore
      }
    }
  }
  
}
