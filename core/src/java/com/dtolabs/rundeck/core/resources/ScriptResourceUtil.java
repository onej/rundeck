/*
 * Copyright 2011 DTO Solutions, Inc. (http://dtosolutions.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.dtolabs.rundeck.core.resources;

import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.INodeSet;
import com.dtolabs.rundeck.core.common.NodeSetImpl;
import com.dtolabs.rundeck.core.common.Nodes;
import com.dtolabs.rundeck.core.dispatcher.DataContextUtils;
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException;
import com.dtolabs.rundeck.core.utils.StringArrayUtil;
import com.dtolabs.utils.Streams;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**
 * Utility class for executing an external script and parsing the output in a particular format, returning INodeSet data.
 */
class ScriptResourceUtil {
    public static INodeSet executeScript(final File scriptfile, final String scriptargs, final String scriptinterpreter,
                                         final String pluginname, final Map<String, Map<String, String>> dataContext,
                                         final Nodes.Format fileformat, final Framework framework,
                                         final String project, final Logger logger) throws ResourceModelSourceException {

        /*
        String dirstring = null;
        dirstring = plugin.get
        if (null != node.getAttributes().get(DIR_ATTRIBUTE)) {
            dirstring = node.getAttributes().get(DIR_ATTRIBUTE);
        }
        if (null != dirstring) {
            workingdir = new File(dirstring);
        }*/

        File workingdir = null;

        int result = -1;
        boolean success = false;
         Streams.StreamCopyThread errthread = null;
         Streams.StreamCopyThread outthread = null;

        final File destinationTempFile;
        try {
            destinationTempFile = File.createTempFile("script-plugin", ".resources");
//            destinationTempFile.deleteOnExit();
        } catch (IOException e) {
            throw new ResourceModelSourceException(e);
        }
        logger.info("Tempfile: " + destinationTempFile.getAbsolutePath());
        final Process exec;
        try {
            if(null!=scriptinterpreter){
                exec = execShellScript(logger, workingdir, scriptfile, scriptargs, dataContext, dataContext,
                    scriptinterpreter, pluginname);
            }else{
                exec = execScript(logger, workingdir, scriptfile, scriptargs, dataContext, dataContext, pluginname);
            }
        } catch (IOException e) {
            throw new ResourceModelSourceException(e);
        }
        try {
            errthread = Streams.copyStreamThread(exec.getErrorStream(), System.err);
            outthread = Streams.copyStreamThread(exec.getInputStream(), new FileOutputStream(destinationTempFile));
            errthread.start();
            outthread.start();
            exec.getOutputStream().close();
            result = exec.waitFor();
            errthread.join();
            outthread.join();
            success = 0 == result;
        } catch (InterruptedException e) {
            logger.error("[" + pluginname + "]: " + e.getMessage());
            e.printStackTrace(System.err);
        } catch (IOException e) {
            logger.error("[" + pluginname + "]: " + e.getMessage());
            e.printStackTrace(System.err);
        }
        logger.debug("[" + pluginname + "]: result code: " + result + ", success: " + success);
        if (null != outthread && null != outthread.getException()) {
            logger.error("[" + pluginname + "]: stream copy error: " + outthread.getException().getMessage(),
                outthread.getException());
        }if (null != errthread && null != errthread.getException()) {
            logger.error("[" + pluginname + "]: stream copy error: " + errthread.getException().getMessage(),
                errthread.getException());
        }
        if(!success) {
            throw new ResourceModelSourceException("Script execution failed with result: " + result);
        }

        if (destinationTempFile.isFile() && destinationTempFile.length() > 0) {
            try {
                return FileResourceModelSource.parseFile(destinationTempFile, fileformat, framework,
                    project);
            } catch (ConfigurationException e) {
                throw new ResourceModelSourceException(e);
            }
        } else {
            throw new ResourceModelSourceException("Script output was empty");
        }
    }

    /**
     * Execute a process by invoking a specified shell command and passing the arguments to the shell.
     *
     * @param logger         logger
     * @param workingdir     working dir
     * @param scriptfile
     * @param scriptargs     arguments to the shell
     * @param envContext     Environment variable context
     * @param newDataContext context data to replace in the scriptargs
     * @param interpreter    the remote shell script, which will be split on whitespace
     * @param logName        name of plugin to use in logging
     */
    static Process execShellScript(final Logger logger, final File workingdir,
                                   final File scriptfile, final String scriptargs,
                                   final Map<String, Map<String, String>> envContext,
                                   final Map<String, Map<String, String>> newDataContext,
                                   final String interpreter, final String logName) throws IOException {

        final ArrayList<String> shells = new ArrayList<String>();
        if(null!=interpreter) {
            shells.addAll(Arrays.asList(interpreter.split(" ")));
        }

        //use script-copy attribute and replace datareferences
        final String newargs = DataContextUtils.replaceDataReferences(scriptargs, newDataContext);
        shells.add(scriptfile.getAbsolutePath() + " " + newargs);

        final ProcessBuilder processBuilder = new ProcessBuilder(shells).directory(workingdir);
        final Map<String, String> environment = processBuilder.environment();
        //create system environment variables from the data context
        environment.putAll(DataContextUtils.generateEnvVarsFromContext(envContext));

        logger.info("[" + logName + "] executing: " +shells);
        return processBuilder.start();
    }

    /**
     * Execute a process directly with some arguments
     *
     * @param logger         logger
     * @param workingdir     working dir
     * @param scriptfile
     * @param scriptargs     arguments to the shell
     * @param envContext     Environment variable context
     * @param newDataContext context data to replace in the scriptargs
     * @param logName        name of plugin to use in logging
     */
    static Process execScript(final Logger logger, final File workingdir, File scriptfile, final String scriptargs,
                              final Map<String, Map<String, String>> envContext,
                              final Map<String, Map<String, String>> newDataContext,
                              final String logName) throws IOException {
        ArrayList<String> list = new ArrayList<String>();
        list.add(scriptfile.getAbsolutePath());
        if (null != scriptargs && !"".equals(scriptargs)) {
            list.addAll(Arrays.asList(DataContextUtils.replaceDataReferences(scriptargs.split(" "), newDataContext)));
        }
        final String[] args = list.toArray(new String[list.size()]);

        //create system environment variables from the data context
        final Map<String, String> envMap = DataContextUtils.generateEnvVarsFromContext(envContext);
        final ArrayList<String> envlist = new ArrayList<String>();
        for (final String key : envMap.keySet()) {
            final String envval = envMap.get(key);
            envlist.add(key + "=" + envval);
        }
        final String[] envarr = envlist.toArray(new String[envlist.size()]);


        logger.info("[" + logName + "] executing: " + StringArrayUtil.asString(args, " "));
        final Runtime runtime = Runtime.getRuntime();
        return runtime.exec(args, envarr, workingdir);
    }
}