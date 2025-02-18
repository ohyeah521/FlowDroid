/*******************************************************************************
 * Copyright (c) 2012 Secure Software Engineering Group at EC SPRIDE.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors: Christian Fritz, Steven Arzt, Siegfried Rasthofer, Eric
 * Bodden, and others.
 ******************************************************************************/
package soot.jimple.infoflow.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import soot.jimple.infoflow.data.SootMethodAndClass;
import soot.util.HashMultiMap;
import soot.util.MultiMap;

/**
 * handles conversion from the string representation of SootMethod to our
 * internal format {@link soot.jimple.infoflow.data.SootMethodAndClass}
 *
 */
public class SootMethodRepresentationParser {

	private static final SootMethodRepresentationParser instance = new SootMethodRepresentationParser();

	private static final Pattern patternSubsigToName = Pattern.compile("^\\s*(.+)\\s+(.+)\\((.*?)\\)\\s*$");
	private static final Pattern patternMethodSig = Pattern
			.compile("<(?<className>.*?): (?<returnType>.*?) (?<methodName>.*?)\\((?<parameters>.*?)\\)>");
	private static final Pattern patternNoReturnValue = Pattern.compile("^\\s*(.+)\\((.*?)\\)\\s*$");

	private SootMethodRepresentationParser() {

	}

	public static SootMethodRepresentationParser v() {
		return instance;
	}

	/**
	 * parses a string in soot representation, for example:
	 * <soot.jimple.infoflow.test.TestNoMain: java.lang.String function1()>
	 * <soot.jimple.infoflow.test.TestNoMain: void functionCallOnObject()>
	 * <soot.jimple.infoflow.test.TestNoMain: java.lang.String
	 * function2(java.lang.String,java.lang.String)>
	 * 
	 * @param parseString The method signature to parse
	 */
	public SootMethodAndClass parseSootMethodString(String parseString) {
		if (parseString == null || parseString.isEmpty())
			return null;

		if (!parseString.startsWith("<") || !parseString.endsWith(">")) {
			throw new IllegalArgumentException(
					"Illegal format of " + parseString + " (should use soot method representation)");
		}

		Matcher matcher = patternMethodSig.matcher(parseString);
		if (matcher.find()) {
			String className = matcher.group("className");
			String returnType = matcher.group("returnType");
			String methodName = matcher.group("methodName");
			String paramList = matcher.group("parameters");
			return new SootMethodAndClass(methodName, className, returnType, paramList);
		}

		return null;
	}

	/*
	 * Returns classname and unresolved! method names and return types and
	 * parameters
	 */
	public HashMap<String, Set<String>> parseClassNames(Collection<String> methods, boolean subSignature) {
		HashMap<String, Set<String>> result = new HashMap<String, Set<String>>();
		Pattern pattern = Pattern.compile("^\\s*<(.*?):\\s*(.*?)>\\s*$");
		for (String parseString : methods) {
			// parse className:
			String className = "";
			Matcher matcher = pattern.matcher(parseString);
			if (matcher.find()) {
				className = matcher.group(1);
				String params = "";
				if (subSignature)
					params = matcher.group(2);
				else
					params = parseString;

				if (result.containsKey(className))
					result.get(className).add(params);
				else {
					Set<String> methodList = new HashSet<String>();
					methodList.add(params);
					result.put(className, methodList);
				}
			}
		}
		return result;
	}

	/*
	 * Returns classname and unresolved! method names and return types and
	 * parameters
	 */
	public MultiMap<String, String> parseClassNames2(Collection<String> methods, boolean subSignature) {
		MultiMap<String, String> result = new HashMultiMap<>();
		Pattern pattern = Pattern.compile("^\\s*<(.*?):\\s*(.*?)>\\s*$");
		for (String parseString : methods) {
			// parse className:
			String className = "";
			Matcher matcher = pattern.matcher(parseString);
			if (matcher.find()) {
				className = matcher.group(1);
				String params = "";
				if (subSignature)
					params = matcher.group(2);
				else
					params = parseString;
				result.put(className, params);
			}
		}
		return result;
	}

	/**
	 * Parses a Soot method subsignature and returns the method name
	 *
	 * @param subSignature The Soot subsignature to parse
	 * @return The name of the method being invoked if the given subsignature could
	 *         be parsed successfully, otherwise an empty string.
	 */
	public String getMethodNameFromSubSignature(String subSignature) {
		Matcher matcher = patternSubsigToName.matcher(subSignature);

		if (!matcher.find()) { // in case no return value exists
			matcher = patternNoReturnValue.matcher(subSignature);
			if (!matcher.find())
				;
			return "";
		}
		String method = matcher.group(matcher.groupCount() - 1);
		return method;
	}

	/**
	 * Parses a Soot method subsignature and returns the list of parameter types
	 *
	 * @param subSignature The Soot subsignature to parse
	 * @return The list of formal parameters in the given subsignature invoked if
	 *         the given subsignature could be parsed successfully, otherwise null.
	 */
	public String[] getParameterTypesFromSubSignature(String subSignature) {
		Matcher matcher = patternSubsigToName.matcher(subSignature);
		if (!matcher.find()) { // in case no return value exists
			matcher = patternNoReturnValue.matcher(subSignature);
			if (!matcher.find())
				;
			return null;
		}
		String params = matcher.group(matcher.groupCount());
		if (params.equals(""))
			return null;
		else
			return params.split("\\s*,\\s*");

	}

}
