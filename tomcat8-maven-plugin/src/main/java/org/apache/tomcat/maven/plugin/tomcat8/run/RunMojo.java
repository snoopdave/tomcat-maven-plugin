package org.apache.tomcat.maven.plugin.tomcat8.run;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.catalina.Context;
import org.apache.catalina.WebResource;
import org.apache.catalina.WebResourceSet;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.webresources.EmptyResource;
import org.apache.catalina.webresources.FileResource;
import org.apache.catalina.webresources.FileResourceSet;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.filtering.MavenFileFilterRequest;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.tomcat.maven.common.run.ClassLoaderEntriesCalculator;
import org.apache.tomcat.maven.common.run.ClassLoaderEntriesCalculatorRequest;
import org.apache.tomcat.maven.common.run.ClassLoaderEntriesCalculatorResult;
import org.apache.tomcat.maven.common.run.TomcatRunException;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.Xpp3DomWriter;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Runs the current project as a dynamic web application using an embedded Tomcat server.
 *
 * @author Olivier Lamy
 * @since 2.0
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class RunMojo
    extends AbstractRunMojo
{
    // ----------------------------------------------------------------------
    // Mojo Parameters
    // ----------------------------------------------------------------------


    /**
     * The set of dependencies for the web application being run.
     */
    @Parameter(defaultValue = "${project.artifacts}", required = true, readonly = true)
    private Set<Artifact> dependencies;

    /**
     * The web resources directory for the web application being run.
     */
    @Parameter(defaultValue = "${basedir}/src/main/webapp", property = "tomcat.warSourceDirectory")
    private File warSourceDirectory;


    /**
     * Set the "follow standard delegation model" flag used to configure our ClassLoader.
     *
     * @see http://tomcat.apache.org/tomcat-7.0-doc/api/org/apache/catalina/loader/WebappLoader.html#setDelegate(boolean)
     * @since 1.0
     */
    @Parameter(property = "tomcat.delegate", defaultValue = "true")
    private boolean delegate = true;

    /**
     * @since 2.0
     */
    @Component
    private ClassLoaderEntriesCalculator classLoaderEntriesCalculator;

    /**
     * will add /WEB-INF/lib/*.jar and /WEB-INF/classes from war dependencies in the webappclassloader
     *
     * @since 2.0
     */
    @Parameter(property = "maven.tomcat.addWarDependenciesInClassloader", defaultValue = "true")
    private boolean addWarDependenciesInClassloader;

    /**
     * will use the test classpath rather than the compile one and will add test dependencies too
     *
     * @since 2.0
     */
    @Parameter(property = "maven.tomcat.useTestClasspath", defaultValue = "false")
    private boolean useTestClasspath;

    /**
     * Additional optional directories to add to the embedded tomcat classpath.
     *
     * @since 2.0
     */
    @Parameter(alias = "additionalClassesDirs")
    private List<String> additionalClasspathDirs;


    public final File getWarSourceDirectory()
    {
        return warSourceDirectory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected File getDocBase()
        throws IOException
    {
        // https://issues.apache.org/jira/browse/MTOMCAT-239
        // when running a jar docBase doesn't exists so create a fake one
        if ( !warSourceDirectory.exists() )
        {
            // we create a temporary file in build.directory
            final File tempDocBase = createTempDirectory( new File( project.getBuild().getDirectory() ) );
            Runtime.getRuntime().addShutdownHook( new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        FileUtils.deleteDirectory( tempDocBase );
                    }
                    catch ( Exception e )
                    {
                        // we can consider as safe to ignore as it's located in build directory
                    }
                }
            } );
            return tempDocBase;
        }
        return warSourceDirectory;
    }

    private static File createTempDirectory( File baseTmpDirectory )
        throws IOException
    {
        final File temp = File.createTempFile( "temp", Long.toString( System.nanoTime() ), baseTmpDirectory );

        if ( !( temp.delete() ) )
        {
            throw new IOException( "Could not delete temp file: " + temp.getAbsolutePath() );
        }

        if ( !( temp.mkdir() ) )
        {
            throw new IOException( "Could not create temp directory: " + temp.getAbsolutePath() );
        }

        return temp;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected File getContextFile()
        throws MojoExecutionException
    {
        File temporaryContextFile = null;

        //----------------------------------------------------------------------------
        // context attributes backgroundProcessorDelay reloadable cannot be modified at runtime.
        // It looks only values from the file are used
        // so here we create a temporary file with values modified
        //----------------------------------------------------------------------------
        FileReader fr = null;
        FileWriter fw = null;
        StringWriter sw = new StringWriter();
        try
        {
            temporaryContextFile = File.createTempFile( "tomcat-maven-plugin", "temp-ctx-file" );
            temporaryContextFile.deleteOnExit();

            // format to modify/create <Context backgroundProcessorDelay="5" reloadable="false">
            if ( contextFile != null && contextFile.exists() )
            {
                MavenFileFilterRequest mavenFileFilterRequest = new MavenFileFilterRequest();
                mavenFileFilterRequest.setFrom( contextFile );
                mavenFileFilterRequest.setTo( temporaryContextFile );
                mavenFileFilterRequest.setMavenProject( project );
                mavenFileFilterRequest.setMavenSession( session );
                mavenFileFilterRequest.setFiltering( true );

                mavenFileFilter.copyFile( mavenFileFilterRequest );

                fr = new FileReader( temporaryContextFile );
                Xpp3Dom xpp3Dom = Xpp3DomBuilder.build( fr );
                xpp3Dom.setAttribute( "backgroundProcessorDelay", Integer.toString( backgroundProcessorDelay ) );
                xpp3Dom.setAttribute( "reloadable", Boolean.toString( isContextReloadable() ) );
                fw = new FileWriter( temporaryContextFile );
                Xpp3DomWriter.write( fw, xpp3Dom );
                Xpp3DomWriter.write( sw, xpp3Dom );
                getLog().debug( " generated context file " + sw.toString() );
            }
            else
            {
                if ( contextReloadable )
                {
                    // don't care about using a complicated xml api to create one xml line :-)
                    StringBuilder sb = new StringBuilder( "<Context " ).append( "backgroundProcessorDelay=\"" ).append(
                        Integer.toString( backgroundProcessorDelay ) ).append( "\"" ).append(
                        " reloadable=\"" + Boolean.toString( isContextReloadable() ) + "\"/>" );

                    getLog().debug( " generated context file " + sb.toString() );
                    fw = new FileWriter( temporaryContextFile );
                    fw.write( sb.toString() );
                }
                else
                {
                    // no user context file and contextReloadable false so no need about creating a hack one
                    return null;
                }
            }
        }
        catch ( IOException e )
        {
            getLog().error( "error creating fake context.xml : " + e.getMessage(), e );
            throw new MojoExecutionException( "error creating fake context.xml : " + e.getMessage(), e );
        }
        catch ( XmlPullParserException e )
        {
            getLog().error( "error creating fake context.xml : " + e.getMessage(), e );
            throw new MojoExecutionException( "error creating fake context.xml : " + e.getMessage(), e );
        }
        catch ( MavenFilteringException e )
        {
            getLog().error( "error filtering context.xml : " + e.getMessage(), e );
            throw new MojoExecutionException( "error filtering context.xml : " + e.getMessage(), e );
        }
        finally
        {
            IOUtil.close( fw );
            IOUtil.close( fr );
            IOUtil.close( sw );
        }

        return temporaryContextFile;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MojoExecutionException
     */
    @Override
    protected WebappLoader createWebappLoader()
        throws IOException, MojoExecutionException
    {
        WebappLoader loader = super.createWebappLoader();

        if ( useSeparateTomcatClassLoader )
        {
            loader.setDelegate( delegate );
        }

        return loader;
    }

    @Override
    protected void enhanceContext( final Context context )
        throws MojoExecutionException
    {
        super.enhanceContext( context );

        try
        {
            ClassLoaderEntriesCalculatorRequest request = new ClassLoaderEntriesCalculatorRequest() //
                .setDependencies( dependencies ) //
                .setLog( getLog() ) //
                .setMavenProject( project ) //
                .setAddWarDependenciesInClassloader( addWarDependenciesInClassloader ) //
                .setUseTestClassPath( useTestClasspath );
            ClassLoaderEntriesCalculatorResult classLoaderEntriesCalculatorResult =
                classLoaderEntriesCalculator.calculateClassPathEntries( request );
            final List<String> classLoaderEntries = classLoaderEntriesCalculatorResult.getClassPathEntries();
            final List<File> tmpDirectories = classLoaderEntriesCalculatorResult.getTmpDirectories();

            final List<String> jarPaths = extractJars( classLoaderEntries );

            Runtime.getRuntime().addShutdownHook( new Thread()
            {
                @Override
                public void run()
                {
                    for ( File tmpDir : tmpDirectories )
                    {
                        try
                        {
                            FileUtils.deleteDirectory( tmpDir );
                        }
                        catch ( IOException e )
                        {
                            // ignore
                        }
                    }
                }
            } );

            if ( classLoaderEntries != null )
            {
                WebResourceSet webResourceSet = new FileResourceSet()
                {
                    @Override
                    public WebResource getResource( String path )
                    {

                        if ( StringUtils.startsWithIgnoreCase( path, "/WEB-INF/LIB" ) )
                        {
                            File file = new File( StringUtils.removeStartIgnoreCase( path, "/WEB-INF/LIB" ) );
                            return new FileResource( context.getResources(), getPath(), file, true );
                        }
                        if ( StringUtils.equalsIgnoreCase( path, "/WEB-INF/classes" ) )
                        {
                            return new FileResource( context.getResources(), getPath(),
                                                     new File( project.getBuild().getOutputDirectory() ), true );
                        }

                        File file = new File( project.getBuild().getOutputDirectory(), path );
                        if ( file.exists() )
                        {
                            return new FileResource( context.getResources(), getPath(), file, true );
                        }

                        if ( StringUtils.endsWith( path, ".class" ) )
                        {
                            // so we search the class file in the jars
                            for ( String jarPath : jarPaths )
                            {
                                File jar = new File( jarPath );
                                if ( !jar.exists() )
                                {
                                    continue;
                                }

                                try
                                {
                                    JarFile jarFile = new JarFile( jar );

                                    if ( jarFile.getEntry( StringUtils.removeStart( path, "/" ) ) != null )
                                    {
                                        return new FileResource( context.getResources(), getPath(), jar, true );
                                    }
                                }
                                catch ( IOException e )
                                {
                                    getLog().debug( "skip error building jar file: " + e.getMessage(), e );
                                }

                            }
                        }

                        return new EmptyResource( null, path );
                    }

                    @Override
                    public String[] list( String path )
                    {
                        if ( StringUtils.startsWithIgnoreCase( path, "/WEB-INF/LIB" ) )
                        {
                            return jarPaths.toArray( new String[jarPaths.size()] );
                        }
                        if ( StringUtils.equalsIgnoreCase( path, "/WEB-INF/classes" ) )
                        {
                            return new String[]{ new File( project.getBuild().getOutputDirectory() ).getPath() };
                        }
                        return super.list( path );
                    }

                    @Override
                    public Set<String> listWebAppPaths( String path )
                    {

                        if ( StringUtils.equalsIgnoreCase( "/WEB-INF/lib/", path ) )
                        {
                            // adding outputDirectory as well?
                            return new HashSet<String>( jarPaths );
                        }

                        File filePath = new File( getWarSourceDirectory(), path );

                        if ( filePath.isDirectory() )
                        {
                            Set<String> paths = new HashSet<String>();

                            String[] files = filePath.list();
                            if ( files == null )
                            {
                                return paths;
                            }

                            for ( String file : files )
                            {
                                paths.add( file );
                            }

                            return paths;

                        }
                        else
                        {
                            return Collections.emptySet();
                        }
                    }

                    @Override
                    public boolean mkdir( String path )
                    {
                        return super.mkdir( path );
                    }

                    @Override
                    public boolean write( String path, InputStream is, boolean overwrite )
                    {
                        return super.write( path, is, overwrite );
                    }

                    @Override
                    protected void checkType( File file )
                    {
                        //super.checkType( file );
                    }


                };

                context.getResources().addJarResources( webResourceSet );
            }

        }
        catch ( TomcatRunException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }

    }


    /**
     * extract List of path which are files (removing directories from the initial list)
     *
     * @param classLoaderEntries
     * @return
     */
    private List<String> extractJars( List<String> classLoaderEntries )
        throws MojoExecutionException
    {

        List<String> jarPaths = new ArrayList<String>();

        try
        {
            for ( String classLoaderEntry : classLoaderEntries )
            {
                URI uri = new URI( classLoaderEntry );
                File file = new File( uri );
                if ( !file.isDirectory() )
                {
                    jarPaths.add( file.getAbsolutePath() );
                }
            }
        }
        catch ( URISyntaxException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }

        return jarPaths;

    }
}