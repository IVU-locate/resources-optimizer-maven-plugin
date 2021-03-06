/*
 * Copyright 2011-2015 PrimeFaces Extensions
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
 *
 * $Id$
 */

package org.primefaces.extensions.optimizerplugin;

import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.jscomp.WarningLevel;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.primefaces.extensions.optimizerplugin.model.Aggregation;
import org.primefaces.extensions.optimizerplugin.model.ResourcesSet;
import org.primefaces.extensions.optimizerplugin.model.SourceMap;
import org.primefaces.extensions.optimizerplugin.optimizer.ClosureCompilerOptimizer;
import org.primefaces.extensions.optimizerplugin.optimizer.YuiCompressorOptimizer;
import org.primefaces.extensions.optimizerplugin.replacer.DataUriTokenResolver;
import org.primefaces.extensions.optimizerplugin.util.ResourcesScanner;
import org.primefaces.extensions.optimizerplugin.util.ResourcesSetAdapter;
import org.primefaces.extensions.optimizerplugin.util.ResourcesSetCssAdapter;
import org.primefaces.extensions.optimizerplugin.util.ResourcesSetJsAdapter;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for this plugin.
 *
 * @author Oleg Varaksin (ovaraksin@googlemail.com)
 * @goal optimize
 * @phase process-resources
 * @threadSafe true
 */
public class ResourcesOptimizerMojo extends AbstractMojo {

    private static final String[] DEFAULT_INCLUDES = {"**/*.css", "**/*.js"};

    private static final String[] DEFAULT_EXCLUDES = {};

    /**
     * Input directory.
     *
     * @parameter property="inputDir" default-value="${project.build.directory}/webapp"
     */
    private File inputDir;

    /**
     * Images directories according to JSF spec.
     *
     * @parameter default-value="${project.basedir}${file.separator}src${file.separator}main${file.separator}webapp${file.separator}resources, ${project.basedir}${file.separator}src${file.separator}main${file.separator}resources${file.separator}META-INF${file.separator}resources"
     */
    private String imagesDir;

    /**
     * Compilation level for Google Closure Compiler.
     *
     * @parameter property="compilationLevel" default-value="SIMPLE_OPTIMIZATIONS"
     */
    private String compilationLevel;

    /**
     * Warning level for Google Closure Compiler.
     *
     * @parameter property="warningLevel" default-value="QUIET"
     */
    private String warningLevel;

    /**
     * Encoding to read files.
     *
     * @parameter property="encoding" default-value="UTF-8"
     * @required
     */
    private String encoding;

    /**
     * Flag whether this plugin must stop/fail on warnings.
     *
     * @parameter
     */
    private boolean failOnWarning = false;

    /**
     * Suffix for compressed / merged files.
     *
     * @parameter
     */
    private String suffix;

    /**
     * Flag if images referenced in CSS files (size < 32KB) should be converted to data URIs.
     *
     * @parameter
     */
    private boolean useDataUri = false;

    /**
     * Files to be included. Files selectors follow patterns specified in {@link org.codehaus.plexus.util.DirectoryScanner}.
     *
     * @parameter
     */
    private String[] includes;

    /**
     * Files to be excluded. Files selectors follow patterns specified in {@link org.codehaus.plexus.util.DirectoryScanner}.
     *
     * @parameter
     */
    private String[] excludes;

    /**
     * Configuration for aggregations.
     *
     * @parameter
     */
    private Aggregation[] aggregations;

    /**
     * Configuration for source maps.
     *
     * @parameter
     */
    private SourceMap sourceMap;

    /**
     * Default output directory for created source maps and original source files.
     * This value is used internally in this class.
     *
     * @parameter default-value="${project.build.directory}${file.separator}sourcemap${file.separator}"
     */
    private String smapOutputDir;


    /**
     * Language mode for input javascript.
     *
     * @parameter property="languageIn" default-value="ECMASCRIPT3"
     */
    private String languageIn;

    /**
     * Language mode for output javascript.
     *
     * @parameter property="languageOut" default-value="NO_TRANSPILE"
     */
    private String languageOut;

    /**
     * Compile sets.
     *
     * @parameter
     */

    private List<ResourcesSet> resourcesSets;

    private DataUriTokenResolver dataUriTokenResolver;

    private long originalFilesSize = 0;

    private long optimizedFilesSize = 0;

    boolean resFound = false;

    /**
     * Executes Mojo.
     *
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        //getLog().info("Optimization of resources is started ...");

        try {
            if (resourcesSets == null || resourcesSets.isEmpty()) {
                String[] incls = (includes != null && includes.length > 0) ? includes : DEFAULT_INCLUDES;
                String[] excls = (excludes != null && excludes.length > 0) ? excludes : DEFAULT_EXCLUDES;

                Aggregation[] aggrs;
                if (aggregations == null || aggregations.length < 1) {
                    aggrs = new Aggregation[1];
                    aggrs[0] = null;
                } else {
                    aggrs = aggregations;
                }

                for (Aggregation aggr : aggrs) {
                    aggr = checkAggregation(aggr) ? null : aggr;

                    // evaluate inputDir
                    File dir = (aggr != null && aggr.getInputDir() != null) ? aggr.getInputDir() : inputDir;

                    // prepare CSS und JavaScript files
                    ResourcesScanner scanner = new ResourcesScanner();
                    scanner.scan(dir, incls, excls);

                    if (aggr != null && aggr.getOutputFile() == null) {
                        // subDirMode = true ==> aggregation for each subfolder
                        File[] files = dir.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.isDirectory()) {
                                    ResourcesScanner subDirScanner = new ResourcesScanner();
                                    subDirScanner.scan(file, DEFAULT_INCLUDES, DEFAULT_EXCLUDES);

                                    Set<File> subDirCssFiles =
                                            filterSubDirFiles(scanner.getCssFiles(), subDirScanner.getCssFiles());
                                    if (!subDirCssFiles.isEmpty()) {
                                        DataUriTokenResolver dataUriTokenResolver =
                                                (useDataUri ? getDataUriTokenResolver() : null);

                                        // handle CSS files
                                        processCssFiles(file, subDirCssFiles, dataUriTokenResolver,
                                                getSubDirAggregation(file, aggr, ResourcesScanner.CSS_FILE_EXTENSION),
                                                null);
                                    }

                                    Set<File> subDirJsFiles = filterSubDirFiles(scanner.getJsFiles(), subDirScanner.getJsFiles());
                                    if (!subDirJsFiles.isEmpty()) {
                                        // handle JavaScript files
                                        processJsFiles(file, subDirJsFiles,
                                                getSubDirAggregation(file, aggr, ResourcesScanner.JS_FILE_EXTENSION),
                                                getCompilationLevel(compilationLevel), getWarningLevel(warningLevel),
                                                resolveSourceMap(null), null, getLanguageIn(languageIn), getLanguageOut(languageOut));
                                    }
                                }
                            }
                        }
                    } else {
                        if (!scanner.getCssFiles().isEmpty()) {
                            DataUriTokenResolver dataUriTokenResolver = (useDataUri ? getDataUriTokenResolver() : null);

                            // handle CSS files
                            processCssFiles(dir, scanner.getCssFiles(), dataUriTokenResolver, aggr, suffix);
                        }

                        if (!scanner.getJsFiles().isEmpty()) {
                            // handle JavaScript files
                            processJsFiles(dir, scanner.getJsFiles(), aggr, getCompilationLevel(compilationLevel),
                                    getWarningLevel(warningLevel), resolveSourceMap(null), suffix,
                                    getLanguageIn(languageIn), getLanguageOut(languageOut));
                        }
                    }
                }
            } else {
                for (ResourcesSet rs : resourcesSets) {
                    // iterate over all resources sets
                    String[] incls;
                    if (rs.getIncludes() != null && rs.getIncludes().length > 0) {
                        incls = rs.getIncludes();
                    } else if (includes != null && includes.length > 0) {
                        incls = includes;
                    } else {
                        incls = DEFAULT_INCLUDES;
                    }

                    String[] excls;
                    if (rs.getExcludes() != null && rs.getExcludes().length > 0) {
                        excls = rs.getExcludes();
                    } else if (excludes != null && excludes.length > 0) {
                        excls = excludes;
                    } else {
                        excls = DEFAULT_EXCLUDES;
                    }

                    Aggregation[] aggrs;
                    if (rs.getAggregations() == null || rs.getAggregations().length < 1) {
                        if (aggregations == null || aggregations.length < 1) {
                            aggrs = new Aggregation[1];
                            aggrs[0] = null;
                        } else {
                            aggrs = aggregations;
                        }
                    } else {
                        aggrs = rs.getAggregations();
                    }

                    for (Aggregation aggr : aggrs) {
                        aggr = checkAggregation(aggr) ? null : aggr;

                        // evaluate inputDir
                        File dir = (aggr != null && aggr.getInputDir() != null) ? aggr.getInputDir() : rs.getInputDir();
                        if (dir == null) {
                            dir = inputDir;
                        }

                        // prepare CSS und JavaScript files
                        ResourcesScanner scanner = new ResourcesScanner();
                        scanner.scan(dir, incls, excls);

                        if (aggr != null && aggr.getOutputFile() == null) {
                            // subDirMode = true ==> aggregation for each subfolder
                            File[] files = dir.listFiles();
                            if (files != null) {
                                for (File file : files) {
                                    if (file.isDirectory()) {
                                        ResourcesScanner subDirScanner = new ResourcesScanner();
                                        subDirScanner.scan(file, DEFAULT_INCLUDES, DEFAULT_EXCLUDES);

                                        Set<File> subDirCssFiles =
                                                filterSubDirFiles(scanner.getCssFiles(), subDirScanner.getCssFiles());
                                        if (!subDirCssFiles.isEmpty()) {
                                            DataUriTokenResolver dataUriTokenResolver =
                                                    (useDataUri || rs.isUseDataUri() ? getDataUriTokenResolver() : null);

                                            // handle CSS files
                                            processCssFiles(file, subDirCssFiles, dataUriTokenResolver,
                                                    getSubDirAggregation(file, aggr, ResourcesScanner.CSS_FILE_EXTENSION),
                                                    null);
                                        }

                                        Set<File> subDirJsFiles =
                                                filterSubDirFiles(scanner.getJsFiles(), subDirScanner.getJsFiles());
                                        if (!subDirJsFiles.isEmpty()) {
                                            // handle JavaScript files
                                            processJsFiles(file, subDirJsFiles,
                                                    getSubDirAggregation(file, aggr, ResourcesScanner.JS_FILE_EXTENSION),
                                                    resolveCompilationLevel(rs), resolveWarningLevel(rs),
                                                    resolveSourceMap(rs), null, resolveLanguageIn(rs), resolveLanguageOut(rs));
                                        }
                                    }
                                }
                            }
                        } else {
                            if (!scanner.getCssFiles().isEmpty()) {
                                DataUriTokenResolver dataUriTokenResolver =
                                        (useDataUri || rs.isUseDataUri() ? getDataUriTokenResolver() : null);

                                // handle CSS files
                                processCssFiles(dir, scanner.getCssFiles(), dataUriTokenResolver, aggr, suffix);
                            }

                            if (!scanner.getJsFiles().isEmpty()) {
                                // handle JavaScript files
                                processJsFiles(dir, scanner.getJsFiles(), aggr, resolveCompilationLevel(rs),
                                        resolveWarningLevel(rs), resolveSourceMap(rs), suffix,
                                        resolveLanguageIn(rs), resolveLanguageOut(rs));
                            }
                        }
                    }
                }
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Error while executing the mojo " + getClass(), e);
        }

        if (!resFound) {
            getLog().info("No resources found for optimization.");

            return;
        }

        //getLog().info("Optimization of resources has been finished successfully.");
        outputStatistic();
    }

    private void processCssFiles(File inputDir, Set<File> cssFiles, DataUriTokenResolver dataUriTokenResolver,
                                 Aggregation aggr, String suffix) throws MojoExecutionException {
        resFound = true;
        ResourcesSetAdapter rsa = new ResourcesSetCssAdapter(
                inputDir, cssFiles, dataUriTokenResolver, aggr, encoding, failOnWarning, suffix);

        YuiCompressorOptimizer yuiOptimizer = new YuiCompressorOptimizer();
        yuiOptimizer.optimize(rsa, getLog());

        originalFilesSize += yuiOptimizer.getTotalOriginalSize();
        optimizedFilesSize += yuiOptimizer.getTotalOptimizedSize();
    }

    private void processJsFiles(File inputDir, Set<File> jsFiles, Aggregation aggr, CompilationLevel compilationLevel,
                                WarningLevel warningLevel, SourceMap sourceMap, String suffix,
                                LanguageMode languageIn, LanguageMode languageOut) throws MojoExecutionException {
        resFound = true;
        ResourcesSetAdapter rsa = new ResourcesSetJsAdapter(
                inputDir, jsFiles, aggr, compilationLevel, warningLevel, sourceMap, encoding,
                failOnWarning, suffix, languageIn, languageOut);

        ClosureCompilerOptimizer closureOptimizer = new ClosureCompilerOptimizer();
        closureOptimizer.optimize(rsa, getLog());

        originalFilesSize += closureOptimizer.getTotalOriginalSize();
        optimizedFilesSize += closureOptimizer.getTotalOptimizedSize();
    }

    private boolean checkAggregation(Aggregation aggregation) throws MojoExecutionException {
        if (aggregation == null) {
            return true;
        }

        if (aggregation.isSubDirMode() && aggregation.getOutputFile() != null) {
            final String errMsg = "At least one aggregation tag is ambiguous because both " +
                    "'subDirMode' and 'outputFile' were set";
            if (failOnWarning) {
                throw new MojoExecutionException(errMsg);
            } else {
                getLog().warn(errMsg);
                getLog().warn("Using 'outputFile' as aggregation");
                aggregation.setSubDirMode(false);
            }

            return false;
        }

        if (!aggregation.isSubDirMode() && aggregation.getOutputFile() == null) {
            final String errMsg =
                    "An aggregation tag is available, but no valid aggregation was configured. " +
                            "Check 'subDirMode' and 'outputFile'";
            if (failOnWarning) {
                throw new MojoExecutionException(errMsg);
            } else {
                getLog().warn(errMsg);
            }

            return true;
        }

        return false;
    }

    private Aggregation getSubDirAggregation(File dir, Aggregation aggr, String fileExtension) {
        Aggregation subDirAggr = new Aggregation();
        subDirAggr.setPrependedFile(aggr.getPrependedFile());
        subDirAggr.setRemoveIncluded(aggr.isRemoveIncluded());
        subDirAggr.setWithoutCompress(aggr.isWithoutCompress());
        subDirAggr.setSubDirMode(true);

        File outputFile = new File(dir, dir.getName() + "." + fileExtension);
        subDirAggr.setOutputFile(outputFile);

        return subDirAggr;
    }

    private CompilationLevel resolveCompilationLevel(ResourcesSet rs) throws MojoExecutionException {
        CompilationLevel compLevel;
        if (rs.getCompilationLevel() != null) {
            compLevel = getCompilationLevel(rs.getCompilationLevel());
        } else {
            compLevel = getCompilationLevel(compilationLevel);
        }

        return compLevel;
    }

    private WarningLevel resolveWarningLevel(ResourcesSet rs) throws MojoExecutionException {
        WarningLevel warnLevel;
        if (rs.getWarningLevel() != null) {
            warnLevel = getWarningLevel(rs.getWarningLevel());
        } else {
            warnLevel = getWarningLevel(warningLevel);
        }

        return warnLevel;
    }

    private SourceMap resolveSourceMap(ResourcesSet rs) {
        SourceMap smap;
        if (rs != null && rs.getSourceMap() != null) {
            smap = rs.getSourceMap();
        } else {
            smap = sourceMap;
        }
        
        if (smap == null || !smap.isCreate()) {
            return null;
        }

        // set defaults
        if (smap.getOutputDir() == null) {
            smap.setOutputDir(smapOutputDir);
        }

        if (smap.getDetailLevel() == null) {
            smap.setDetailLevel(com.google.javascript.jscomp.SourceMap.DetailLevel.ALL.name());
        }

        if (smap.getFormat() == null) {
            smap.setFormat(com.google.javascript.jscomp.SourceMap.Format.V3.name());
        }

        return smap;
    }

    private LanguageMode resolveLanguageIn(ResourcesSet rs) throws MojoExecutionException {
        LanguageMode langIn;
        if (rs.getLanguageIn() != null) {
            langIn = getLanguageIn(rs.getLanguageIn());
        } else {
            langIn = getLanguageIn(languageIn);
        }

        return langIn;
    }

    private LanguageMode resolveLanguageOut(ResourcesSet rs) throws MojoExecutionException {
        LanguageMode langOut;
        if (rs.getLanguageOut() != null) {
            langOut = getLanguageOut(rs.getLanguageOut());
        } else {
            langOut = getLanguageIn(languageOut);
        }

        return langOut;
    }

    private CompilationLevel getCompilationLevel(String compilationLevel) throws MojoExecutionException {
        try {
            return CompilationLevel.valueOf(compilationLevel);
        } catch (Exception e) {
            final String errMsg =
                    "Compilation level '" + compilationLevel + "' is wrong. Valid constants are: " +
                            "'WHITESPACE_ONLY', 'SIMPLE_OPTIMIZATIONS', 'ADVANCED_OPTIMIZATIONS'";
            if (failOnWarning) {
                throw new MojoExecutionException(errMsg);
            } else {
                getLog().warn(errMsg);
                getLog().warn("Using 'SIMPLE_OPTIMIZATIONS' as compilation level");

                return CompilationLevel.SIMPLE_OPTIMIZATIONS;
            }
        }
    }

    private WarningLevel getWarningLevel(String warningLevel) throws MojoExecutionException {
        try {
            return WarningLevel.valueOf(warningLevel);
        } catch (Exception e) {
            final String errMsg =
                    "Warning level '" + warningLevel + "' is wrong. Valid constants are: 'QUIET', 'DEFAULT', 'VERBOSE'";
            if (failOnWarning) {
                throw new MojoExecutionException(errMsg);
            } else {
                getLog().warn(errMsg);
                getLog().warn("Using 'QUIET' as warning level");

                return WarningLevel.QUIET;
            }
        }
    }

    private LanguageMode getLanguageIn(String languageIn) throws MojoExecutionException {
        try {
            return LanguageMode.valueOf(languageIn);
        } catch (Exception e) {
            final String errMsg =
                    "Input language spec'" + languageIn + "' is wrong. Valid constants are: 'ECMASCRIPT3', " +
                            "'ECMASCRIPT5','ECMASCRIPT5_STRICT','ECMASCRIPT6','ECMASCRIPT6_STRICT'," +
                            "'ECMASCRIPT6_TYPED'";
            if (failOnWarning) {
                throw new MojoExecutionException(errMsg);
            } else {
                getLog().warn(errMsg);
                getLog().warn("Using 'QUIET' as warning level");

                return LanguageMode.ECMASCRIPT3;
            }
        }
    }

    private LanguageMode getLanguageOut(String languageOut) throws MojoExecutionException {
        try {
            return LanguageMode.valueOf(languageOut);
        } catch (Exception e) {
            final String errMsg =
                    "Output language spec'" + languageOut + "' is wrong. Valid constants are: 'ECMASCRIPT3', " +
                            "'ECMASCRIPT5', 'ECMASCRIPT5_STRICT', 'ECMASCRIPT6_TYPED' (experimental)";
            if (failOnWarning) {
                throw new MojoExecutionException(errMsg);
            } else {
                getLog().warn(errMsg);
                getLog().warn("Using 'QUIET' as warning level");

                return LanguageMode.NO_TRANSPILE;
            }
        }
    }

    private DataUriTokenResolver getDataUriTokenResolver() {
        if (dataUriTokenResolver != null) {
            return dataUriTokenResolver;
        }

        String[] arrImagesDir = imagesDir.split(",");
        File[] fileImagesDir = new File[arrImagesDir.length];
        for (int i = 0; i < arrImagesDir.length; i++) {
            fileImagesDir[i] = new File(arrImagesDir[i]);
        }

        dataUriTokenResolver = new DataUriTokenResolver(fileImagesDir);

        return dataUriTokenResolver;
    }

    private Set<File> filterSubDirFiles(Set<File> resSetFiles, Set<File> subDirFiles) {
        Set<File> filteredFiles = new LinkedHashSet<File>();

        if (subDirFiles == null || subDirFiles.isEmpty() || resSetFiles == null || resSetFiles.isEmpty()) {
            return filteredFiles;
        }

        for (File subDirFile : subDirFiles) {
            if (resSetFiles.contains(subDirFile)) {
                filteredFiles.add(subDirFile);
            }
        }

        return filteredFiles;
    }

    private void outputStatistic() {
        final String originalSizeTotal;
        final String optimizedSizeTotal;
        long oneMB = 1024 * 1024;

        if (originalFilesSize <= 1024) {
            originalSizeTotal = originalFilesSize + " Bytes";
        } else if (originalFilesSize <= oneMB) {
            originalSizeTotal = round((double) originalFilesSize / 1024, 3) + " KB";
        } else {
            originalSizeTotal = round((double) originalFilesSize / oneMB, 3) + " MB";
        }

        if (optimizedFilesSize <= 1024) {
            optimizedSizeTotal = optimizedFilesSize + " Bytes";
        } else if (optimizedFilesSize <= oneMB) {
            optimizedSizeTotal = round((double) optimizedFilesSize / 1024, 3) + " KB";
        } else {
            optimizedSizeTotal = round((double) optimizedFilesSize / oneMB, 3) + " MB";
        }

        if (originalFilesSize > 0) {
            getLog().info("=== Statistic ===========================================");
            getLog().info("Size before optimization = " + originalSizeTotal);
            getLog().info("Size after optimization = " + optimizedSizeTotal);
            getLog().info("Optimized resources have " + round(((optimizedFilesSize * 100.0) / originalFilesSize), 2)
                    + "% of original size");
            getLog().info("=========================================================");
        }
    }

    private double round(double value, int places) {
        double roundedValue;
        double factor = Math.pow(10.0, places);
        double temp = Math.round(value * factor * factor) / factor;
        roundedValue = Math.round(temp) / factor;

        return roundedValue;
    }
}
