/*******************************************************************************
 * Copyright (c) 2012 itemis AG (http://www.itemis.de).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.franca.connectors.etrice.ui.handlers;

import java.io.File;
import java.util.Collection;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.console.MessageConsoleStream;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.validation.Issue;
import org.franca.connectors.etrice.ROOMConnector;
import org.franca.connectors.etrice.ROOMModelContainer;
import org.franca.core.dsl.FrancaPersistenceManager;
import org.franca.core.dsl.ui.util.SpecificConsole;
import org.franca.core.franca.FModel;
import org.franca.core.utils.FrancaRecursiveValidator;

import com.google.inject.Inject;

public class GenerateETriceXMLHandler extends AbstractHandler {

	static final String MODELLIB_PROJECT = "org.eclipse.etrice.modellib.java";

	@Inject FrancaPersistenceManager loader;
	@Inject FrancaRecursiveValidator validator;
	@Inject ROOMConnector roomConn;

	@SuppressWarnings({ "deprecation", "incomplete-switch" })
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		
        if (selection != null && selection instanceof IStructuredSelection) {
        	SpecificConsole myConsole = new SpecificConsole("Franca");
            final MessageConsoleStream out = myConsole.getOut();
            final MessageConsoleStream err = myConsole.getErr();
            
            if (selection.isEmpty()) {
            	err.println("Please select exactly one file with extension 'fidl'!");
                return null;
            }

            IFile file = (IFile) ((IStructuredSelection) selection).getFirstElement();
            URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
            String outputDir = file.getParent().getLocation().toString();

    		// load Franca IDL file
            String filename = file.getLocationURI().toString();
            out.println("Loading Franca IDL file '" + filename + "' ...");
        	URI rootURI = URI.createURI("classpath:/");
    		FModel fmodel = loader.loadModel(uri, rootURI);
    		if (fmodel==null) {
    			err.println("Couldn't load Franca IDL file '" + filename + "'.");
    			return null;
    		}
    		out.println("Franca IDL: package '" + fmodel.getName() + "'");
    		
    		// validate resource
    		Collection<Issue> issues = validator.validate(fmodel.eResource());
    		int nErrors = 0;
    		for(Issue issue : issues) {
    			switch (issue.getSeverity()) {
    			case INFO:
    			case WARNING:
    				out.println(issue.toString());
    				break;
    			case ERROR:
    				err.println(issue.toString());
    				nErrors++;
    				break;
    			}
    		}
    		if (nErrors>0) {
    			err.println("Aborting due to validation errors!");
    			return null;
    		}
  
    		// set path to eTrice's modellib project
    		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
    		IProject modellib = root.getProject(MODELLIB_PROJECT);
    		roomConn.setModellibFolder(
    				root.getLocation().toString() +
    				modellib.getFullPath().toString() + File.separator +
    				"models");

    		// transform ROOM model (i.e., eTrice file)
    		out.println("Transforming to eTrice model file ...");
    		ROOMModelContainer room = null;
    		try {
    			room = (ROOMModelContainer) roomConn.fromFranca(fmodel);
    		} catch (Exception e) {
    			err.println("Exception during transformation: " + e.toString());
    			for(StackTraceElement f : e.getStackTrace()) {
    				err.println("\tat " + f.toString());
    			}
    			err.println("Internal transformation error, aborting.");
				return null;
    		}
    		
    		// save eTrice file
    		int ext = file.getName().lastIndexOf("." + file.getFileExtension());
    		String outfile = file.getName().substring(0, ext) + ".room";
    		String outpath = outputDir + "/" + outfile;
    		if (roomConn.saveModel(room, outpath)) {
    			out.println("Saved eTrice file '" + outpath + "'.");
    		} else {
    			err.println("eTrice model couldn't be written to file '" + outpath + "'.");
    		}
            
    		// refresh IDE (in order to make new files visible)
            IProject project = file.getProject();
            try {
    			project.refreshLocal(IResource.DEPTH_INFINITE, null);;
			} catch (CoreException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
		return null;
	}
}
