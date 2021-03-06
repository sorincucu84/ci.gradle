/**
 * (C) Copyright IBM Corporation 2014, 2018.
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
package net.wasdev.wlp.gradle.plugins.tasks

import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.GradleException
import groovy.util.XmlParser
import groovy.lang.Tuple
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.plugins.ear.descriptor.EarModule
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.DependencySet
import org.apache.commons.io.FilenameUtils
import org.gradle.api.file.FileCollection
import org.w3c.dom.Element;
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.text.MessageFormat
import org.apache.commons.io.FilenameUtils

import org.gradle.api.Task
import org.gradle.api.tasks.bundling.War
import org.gradle.plugins.ear.Ear
import net.wasdev.wlp.gradle.plugins.utils.*

class InstallAppsTask extends AbstractServerTask {

    protected ApplicationXmlDocument applicationXml = new ApplicationXmlDocument();

    @TaskAction
    void installApps() {
        configureApps(project)

        if (server.apps != null && !server.apps.isEmpty()) {
            createApplicationFolder('apps')
            Tuple appsLists = splitAppList(server.apps)
            installMultipleApps(appsLists[0], 'apps')
            installFileList(appsLists[1], 'apps')
        }
        if (server.dropins != null && !server.dropins.isEmpty()) {
            createApplicationFolder('dropins')
            Tuple dropinsLists = splitAppList(server.dropins)
            installMultipleApps(dropinsLists[0], 'dropins')
            installFileList(dropinsLists[1], 'dropins')
        }

        File libertyConfigDropinsAppXml = ApplicationXmlDocument.getApplicationXmlFile(getServerDir(project))

        if (applicationXml.hasChildElements()) {
            logger.warn("At least one application is not defined in the server configuration but the build file indicates it should be installed in the apps folder. Application configuration is being added to the target server configuration dropins folder by the plug-in.")
            applicationXml.writeApplicationXmlDocument(getServerDir(project))
            writeServerPropertiesToXml(project)
        } else if (hasConfiguredApp(libertyConfigDropinsAppXml)) {
            logger.warn("At least one application is not defined in the server configuration but the build file indicates it should be installed in the apps folder. Liberty will use additional application configuration added to the the target server configuration dropins folder by the plug-in.")
        } else {
            if (libertyConfigDropinsAppXml.exists()){
                libertyConfigDropinsAppXml.delete()
                writeServerPropertiesToXml(project)
            }
        }
    }

    private void installMultipleApps(List<Task> applications, String appsDir) {
        applications.each{ Task task ->
          installProject(task, appsDir)
        }
    }

    private void installProjectArchive(Task task, String appsDir){
      Files.copy(task.archivePath.toPath(), new File(getServerDir(project), "/" + appsDir + "/" + getArchiveName(task)).toPath(), StandardCopyOption.REPLACE_EXISTING)
      validateAppConfig(getArchiveName(task), task.baseName, appsDir)
    }

    protected void installProject(Task task, String appsDir) throws Exception {
      if(isSupportedType()) {
        if(server.looseApplication){
          installLooseApplication(task, appsDir)
        } else {
          installProjectArchive(task, appsDir)
        }
      } else {
        throw new GradleException(MessageFormat.format("Application {0} is not supported", task.archiveName))
      }
    }

    private void installLooseApplication(Task task, String appsDir) throws Exception {
      String looseConfigFileName = getLooseConfigFileName(task)
      String application = looseConfigFileName.substring(0, looseConfigFileName.length()-4)
      File destDir = new File(getServerDir(project), appsDir)
      File looseConfigFile = new File(destDir, looseConfigFileName)
      LooseConfigData config = new LooseConfigData()
      switch(getPackagingType()){
        case "war":
            validateAppConfig(application, task.baseName, appsDir)
            logger.info(MessageFormat.format(("Installing application into the {0} folder."), looseConfigFile.getAbsolutePath()))
            installLooseConfigWar(config, task)
            deleteApplication(new File(getServerDir(project), "apps"), looseConfigFile)
            deleteApplication(new File(getServerDir(project), "dropins"), looseConfigFile)
            config.toXmlFile(looseConfigFile)
            break
        case "ear":
            if ((String.valueOf(project.getGradle().getGradleVersion().charAt(0)) as int) < 4) {
                throw new Exception(MessageFormat.format(("Loose Ear is only supported by Gradle 4.0 or higher")))
            }
            validateAppConfig(application, task.baseName, appsDir)
            logger.info(MessageFormat.format(("Installing application into the {0} folder."), looseConfigFile.getAbsolutePath()))
            installLooseConfigEar(config, task)
            deleteApplication(new File(getServerDir(project), "apps"), looseConfigFile)
            deleteApplication(new File(getServerDir(project), "dropins"), looseConfigFile)
            config.toXmlFile(looseConfigFile)
            break
        default:
            logger.info(MessageFormat.format(("Loose application configuration is not supported for packaging type {0}. The project artifact will be installed as an archive file."),
                    project.getPackaging()))
            installProjectArchive(task, appsDir)
            break
        }
    }

    protected void installLooseConfigWar(LooseConfigData config, Task task) throws Exception {
        Task compileJava = task.getProject().tasks.findByPath(':compileJava')

        File outputDir;
        if(compileJava != null){
            outputDir = compileJava.destinationDir
        }

        if (outputDir != null && !outputDir.exists() && hasJavaSourceFiles(task.classpath, outputDir)) {
          logger.warn(MessageFormat.format("Installed loose application from project {0}, but the project has not been compiled.", project.name))
        }
        LooseWarApplication looseWar = new LooseWarApplication(task, config)
        looseWar.addSourceDir()
        looseWar.addOutputDir(looseWar.getDocumentRoot() , task, "/WEB-INF/classes/");

        //retrieves dependent library jar files
        addWarEmbeddedLib(looseWar.getDocumentRoot(), looseWar, task);

        //add Manifest file
        File manifestFile = new File(project.buildDir.getAbsolutePath() + "/tmp/war/MANIFEST.MF")
        looseWar.addManifestFile(manifestFile)
    }

    private boolean hasJavaSourceFiles(FileCollection classpath, File outputDir){
        for(File f: classpath) {
            if(f.getAbsolutePath().equals(outputDir.getCanonicalPath())) {
                return true;
            }
        }
        return false;
    }

    private void addWarEmbeddedLib(Element parent, LooseWarApplication looseApp, Task task) throws Exception {
      ArrayList<File> deps = new ArrayList<File>();
      task.classpath.each {deps.add(it)}
      //Removes WEB-INF/lib/main directory since it is not rquired in the xml
      if(deps != null && !deps.isEmpty()) {
        deps.remove(0)
      }
      File parentProjectDir = new File(task.getProject().getRootProject().rootDir.getAbsolutePath())
      for (File dep: deps) {
        String dependentProjectName = "project ':"+getProjectPath(parentProjectDir, dep)+"'"
        Project siblingProject = project.getRootProject().findProject(dependentProjectName)
        boolean isCurrentProject = ((task.getProject().toString()).equals(dependentProjectName))
        if (!isCurrentProject && siblingProject != null){
            Element archive = looseApp.addArchive(parent, "/WEB-INF/lib/"+ dep.getName());
            looseApp.addOutputDirectory(archive, siblingProject, "/");
            Task resourceTask = siblingProject.getTasks().findByPath(":"+dependentProjectName+":processResources");
            if (resourceTask.getDestinationDir() != null){
                looseApp.addOutputDir(archive, resourceTask.getDestinationDir(), "/");
            }
            looseApp.addManifestFile(archive, siblingProject);
        } else if(FilenameUtils.getExtension(dep.getAbsolutePath()).equalsIgnoreCase("jar")){
            looseApp.getConfig().addFile(parent, dep.getAbsolutePath() , "/WEB-INF/lib/" + dep.getName());
        } else {
            looseApp.addOutputDir(looseApp.getDocumentRoot(), dep.getAbsolutePath() , "/WEB-INF/classes/");
        }
      }
    }

    protected void installLooseConfigEar(LooseConfigData config, Task task) throws Exception{
        LooseEarApplication looseEar = new LooseEarApplication(task, config);
        looseEar.addSourceDir();
        looseEar.addApplicationXmlFile();

        File[] filesAsDeps = task.getProject().configurations.deploy.getFiles().toArray()
        Dependency[] deps = task.getProject().configurations.deploy.getAllDependencies().toArray()
        HashMap<File, Dependency> completeDeps = new HashMap<File, Dependency>();
        if(filesAsDeps.size() == deps.size()){
            for(int i = 0; i<filesAsDeps.size(); i++) {
                completeDeps.put(filesAsDeps[i], deps[i])
            }
        }

        logger.info(MessageFormat.format("Number of compile dependencies for " + task.project.name + " : " + completeDeps.size()))
        for (Map.Entry<File, Dependency> entry : completeDeps){
            Dependency dependency = entry.getValue();
            File dependencyFile = entry.getKey();

            if (dependency instanceof ProjectDependency) {
                Project dependencyProject = dependency.getDependencyProject()
                String projectType = FilenameUtils.getExtension(dependencyFile.toString())
                switch (projectType) {
                        case "jar":
                        case "ejb":
                        case "rar":
                            looseEar.addJarModule(dependencyProject)
                            break;
                        case "war":
                            Element warElement = looseEar.addWarModule(dependencyProject)
                            addEmbeddedLib(warElement, dependencyProject, looseEar, "/WEB-INF/lib/")
                            break;
                        default:
                            logger.warn('Application ' + dependencyProject.getName() + ' is expressed as ' + projectType + ' which is not a supported input type. Define applications using Task or File objects of type war, ear, or jar.')
                            break;
                    }
            }
            else if (dependency instanceof ExternalModuleDependency) {
                looseEar.getConfig().addFile(dependencyFile.getAbsolutePath(), "/WEB-INF/lib/" + it.getName())
            }
            else {
                logger.warn("Dependency " + dependency.getName() + "could not be added to the looseApplication, as it is neither a ProjectDependency or ExternalModuleDependency")
            }
        }
        File manifestFile = new File(project.buildDir.getAbsolutePath() + "/tmp/ear/MANIFEST.MF")
        looseEar.addManifestFile(manifestFile)
    }
    private void addEmbeddedLib(Element parent, Project proj, LooseApplication looseApp, String dir) throws Exception {
        //Get only the compile dependencies that are included in the war
        File[] filesAsDeps = proj.configurations.compile.minus(proj.configurations.providedCompile).getFiles().toArray()
        for (File f : filesAsDeps){
            String extension = FilenameUtils.getExtension(f.getAbsolutePath())
            if(extension.equals("jar")){
                looseApp.getConfig().addFile(parent, f.getAbsolutePath(),
                        dir + f.getName());
            }
        }
    }

    private String getProjectPath(File parentProjectDir, File dep) {
        String dependencyPathPortion = dep.getAbsolutePath().replace(parentProjectDir.getAbsolutePath()+"/","")
        String projectPath = dep.getAbsolutePath().replace(dependencyPathPortion,"")
        Pattern pattern = Pattern.compile("/build/.*")
        Matcher matcher = pattern.matcher(dependencyPathPortion)
        projectPath = matcher.replaceAll("")
        return projectPath;
    }

    private boolean isSupportedType(){
      switch (getPackagingType()) {
        case "ear":
        case "war":
            return true;
        default:
            return false;
        }
    }
    private String getLooseConfigFileName(Task task){
      return getArchiveName(task) + ".xml"
    }

    //Cleans up the application if the install style is switched from loose application to archive and vice versa
    protected void deleteApplication(File parent, File artifactFile) throws IOException {
        deleteApplication(parent, artifactFile.getName());
        if (artifactFile.getName().endsWith(".xml")) {
            deleteApplication(parent, artifactFile.getName().substring(0, artifactFile.getName().length() - 4));
        } else {
            deleteApplication(parent, artifactFile.getName() + ".xml");
        }
    }

    protected void deleteApplication(File parent, String filename) throws IOException {
        File application = new File(parent, filename);
        if (application.isDirectory()) {
            FileUtils.deleteDirectory(application);
        } else {
            application.delete();
        }
    }

    protected void installFromFile(File file, String appsDir) {
        Files.copy(file.toPath(), new File(getServerDir(project).toString() + '/' + appsDir + '/' + file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        validateAppConfig(file.name, file.name.take(file.name.lastIndexOf('.')), appsDir)
        if (server.looseApplication) {
            logger.warn('Application ' + file.getName() + ' was installed as a file as specified. To install as a loose application, specify the plugin or task generating the archive. ')
        }
    }

    protected void installFileList(List<File> appFiles, String appsDir) {
        appFiles.each { File appFile ->
            installFromFile(appFile, appsDir)
        }
    }

    //Checks if there is an app configured in an existing configDropins application xml file
    private boolean hasConfiguredApp(File applicationXmlFile) {
        if (applicationXmlFile.exists()) {
            ApplicationXmlDocument appXml = new ApplicationXmlDocument()
            appXml.createDocument(applicationXmlFile)
            return appXml.hasChildElements()
        }
        return false
    }

    void createApplicationFolder(String appDir) {
        File serverDir = getServerDir(project)
        File applicationDirectory = new File(serverDir, appDir)

        try {
            if (!applicationDirectory.exists()) {
                applicationDirectory.mkdir()
            }
        } catch (Exception e) {
            throw new GradleException("There was a problem creating ${applicationDirectory.getCanonicalPath()}.", e)
        }
    }
}
