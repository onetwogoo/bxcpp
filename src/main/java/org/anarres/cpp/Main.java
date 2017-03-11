/*
 * Anarres C Preprocessor
 * Copyright (c) 2007-2015, Shevek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.anarres.cpp;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;
import java.util.*;
import javax.annotation.Nonnull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.pcollections.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * (Currently a simple test class).
 */
public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    @Nonnull
    private static CharSequence getWarnings() {
        StringBuilder buf = new StringBuilder();
        for (Warning w : Warning.values()) {
            if (buf.length() > 0)
                buf.append(", ");
            String name = w.name().toLowerCase();
            buf.append(name.replace('_', '-'));
        }
        return buf;
    }

    static class Result {
        Preprocessor preprocessor;
        List<TokenS> original;
        List<TokenS> produced = new ArrayList<>();
        List<Action> actions;
    }

    public static void main(String[] args) throws Exception {
        Result result = preprocess(args);
//        System.out.printf("original: %s\n", result.original);
//        System.out.printf("produced: %s\n", result.produced);
//        System.out.printf("actions: %s\n", result.actions);
//        FileUtils.writeStringToFile(new File("/Users/kaoet/Desktop/actions.txt"), result.actions.toString());

        if (!checkSelfConsistency(result)) return;
        if (!checkIdentityChange(result)) return;

//        PrintStream writer = new PrintStream("/Users/kaoet/Desktop/playback.txt");
//        result.preprocessor.setCurrentState(result.actions.get(0).beforeEnv, ConsPStack.from(result.original));
//        for (;;){
//            TokenS token = result.preprocessor.token();
//            writer.printf("t:%s rest:%s\n", token, result.preprocessor.getRestTokens());
//            if (token.token.getType() == Token.EOF) {
//                break;
//            }
//        }
//        writer.close();
//        if (true) return;


    }

    static boolean checkSelfConsistency(Result result) {
        System.out.println("Checking self consistency");
        Deque<TokenS> input = new LinkedList<>(result.original);

        List<TokenS> replayed = replay(input,result.actions);
//        System.out.printf("replayed: %s\n", replayed);

        for (int i = 0; i < result.produced.size() && i < replayed.size(); i++) {
            TokenS exp = replayed.get(i);
            TokenS act = result.produced.get(i);
            if (!exp.equals(act)) {
                System.out.println("Replayed " + exp + " produced " + act);
                return false;
            }
        }
        if (replayed.size() > result.produced.size()) {
            System.out.println("More tokens in replayed");
            return false;
        } else if (replayed.size() < result.produced.size()) {
            System.out.println("More tokens in produced");
            return false;
        }
        return true;
    }

    static boolean checkIdentityChange(Result result) {
        System.out.println("Checking identity change");
        Backward backward = new Backward(result.preprocessor);
        PVector<PSequence<TokenS>> changes = Empty.vector();
        for (TokenS tokenS: result.produced) {
            changes = changes.plus(TreePVector.singleton(tokenS));
        }
        List<PSequence<TokenS>> orignalChanges = backward.backward(changes, result.actions);
        if (orignalChanges == null) {
            System.out.println("Backward failure");
            return false;
        }
        PVector<PSequence<TokenS>> expectedOriginalChagnes = Empty.vector();
        for (TokenS tokenS: result.original) {
            expectedOriginalChagnes = expectedOriginalChagnes.plus(TreePVector.singleton(tokenS));
        }
        if (!expectedOriginalChagnes.equals(orignalChanges)) {
            System.out.println("Expected:" + expectedOriginalChagnes);
            System.out.println("Got     :" + orignalChanges);
            return false;
        }
        return true;
    }

    static List<TokenS> replay(Deque<TokenS> input, List<Action> actions) {
        List<TokenS> result = new ArrayList<TokenS>();
        for (Action action : actions) {
//            System.out.println("Action:" + action + " rest:" + input);
            if (action instanceof Skip) {
                TokenS actual = ((Skip)action).token;
                TokenS expected = input.removeFirst();
                if (!expected.equals(actual)) {
                    throw new RuntimeException("Skipping " + actual + ", found " + expected + " input " +input);
                }
                result.add(actual);
            } else {
                Replace replace = (Replace)action;
                for (TokenS actual: replace.original) {
                    TokenS expected = input.removeFirst();
                    if (!expected.equals(actual)) {
                        throw new RuntimeException("Expected " + expected + " old " + actual + " instead\n" + replace.toJson());
                    }
                }
                List<TokenS> replSeq = new ArrayList<TokenS>();
                for (MapSeg mapSeg:replace.mapping) {
                    if (mapSeg instanceof New) {
                        for (Token token: ((New)mapSeg).tokens) {
                            replSeq.add(new TokenS(token, Empty.bag()));
                        }
                    } else {
                        Sub sub = (Sub)mapSeg;
                        Deque<TokenS> subInput = new LinkedList<>();
                        for (int i :sub.indicies) {
                            subInput.add(replace.original.get(i));
                        }
                        for (TokenS tokenS: replay(subInput, sub.actions)) {
                            replSeq.add(tokenS);
                        }
                    }
                }
                for (int i = replSeq.size() - 1; i>=0;i--) {
                    TokenS tokenS = replSeq.get(i);
                    input.addFirst(new TokenS(tokenS.token, tokenS.disables.plusAll(replace.disables)));
                }
            }
        }
        return result;
    }

    public static Result preprocess(String[] args) throws Exception {

        OptionParser parser = new OptionParser();
        OptionSpec<?> helpOption = parser.accepts("help",
                "Displays command-line help.")
                .forHelp();
        OptionSpec<?> debugOption = parser.acceptsAll(Arrays.asList("debug"),
                "Enables debug output.");

        OptionSpec<String> defineOption = parser.acceptsAll(Arrays.asList("define", "D"),
                "Defines the given macro.")
                .withRequiredArg().ofType(String.class).describedAs("name[=definition]");
        OptionSpec<String> undefineOption = parser.acceptsAll(Arrays.asList("undefine", "U"),
                "Undefines the given macro, previously either builtin or defined using -D.")
                .withRequiredArg().describedAs("name");
        OptionSpec<File> includeOption = parser.accepts("include",
                "Process file as if \"#" + "include \"file\"\" appeared as the first line of the primary source file.")
                .withRequiredArg().ofType(File.class).describedAs("file");
        OptionSpec<File> incdirOption = parser.acceptsAll(Arrays.asList("incdir", "I"),
                "Adds the directory dir to the list of directories to be searched for header files.")
                .withRequiredArg().ofType(File.class).describedAs("dir");
        OptionSpec<File> iquoteOption = parser.acceptsAll(Arrays.asList("iquote"),
                "Adds the directory dir to the list of directories to be searched for header files included using \"\".")
                .withRequiredArg().ofType(File.class).describedAs("dir");
        OptionSpec<String> warningOption = parser.acceptsAll(Arrays.asList("warning", "W"),
                "Enables the named warning class (" + getWarnings() + ").")
                .withRequiredArg().ofType(String.class).describedAs("warning");
        OptionSpec<Void> noWarningOption = parser.acceptsAll(Arrays.asList("no-warnings", "w"),
                "Disables ALL warnings.");
        OptionSpec<File> inputsOption = parser.nonOptions()
                .ofType(File.class).describedAs("Files to process.");

        OptionSet options = parser.parse(args);

        if (options.has(helpOption)) {
            parser.printHelpOn(System.out);
            return null;
        }

        Preprocessor pp = new Preprocessor();
        pp.addFeature(Feature.DIGRAPHS);
        pp.addFeature(Feature.TRIGRAPHS);
        pp.addFeature(Feature.PRAGMA_ONCE);
        //pp.addFeature(Feature.LINEMARKERS);
        pp.addWarning(Warning.IMPORT);
        pp.setListener(new DefaultPreprocessorListener());
        pp.addMacro("__JCPP__");
        pp.getSystemIncludePath().add("/usr/local/include");
        pp.getSystemIncludePath().add("/usr/include");
        pp.getFrameworksPath().add("/System/Library/Frameworks");
        pp.getFrameworksPath().add("/Library/Frameworks");
        pp.getFrameworksPath().add("/Local/Library/Frameworks");

        if (options.has(debugOption))
            pp.addFeature(Feature.DEBUG);

        if (options.has(noWarningOption))
            pp.getWarnings().clear();

        for (String warning : options.valuesOf(warningOption)) {
            warning = warning.toUpperCase();
            warning = warning.replace('-', '_');
            if (warning.equals("ALL"))
                pp.addWarnings(EnumSet.allOf(Warning.class));
            else
                pp.addWarning(Enum.valueOf(Warning.class, warning));
        }

        for (String arg : options.valuesOf(defineOption)) {
            int idx = arg.indexOf('=');
            if (idx == -1)
                pp.addMacro(arg);
            else
                pp.addMacro(arg.substring(0, idx), arg.substring(idx + 1));
        }
        for (String arg : options.valuesOf(undefineOption)) {
            pp.getMacros().remove(arg);
        }

        for (File dir : options.valuesOf(incdirOption))
            pp.getSystemIncludePath().add(dir.getAbsolutePath());
        for (File dir : options.valuesOf(iquoteOption))
            pp.getQuoteIncludePath().add(dir.getAbsolutePath());
        for (File file : options.valuesOf(includeOption))
            // Comply exactly with spec.
            pp.addInput(new StringLexerSource("#" + "include \"" + file + "\"\n"));

        List<File> inputs = options.valuesOf(inputsOption);
        if (inputs.isEmpty()) {
            pp.addInput(new InputLexerSource(System.in));
        } else {
            for (File input : inputs)
                pp.addInput(new FileLexerSource(input));
        }

        if (pp.getFeature(Feature.DEBUG)) {
            LOG.info("#" + "include \"...\" search starts here:");
            for (String dir : pp.getQuoteIncludePath())
                LOG.info("  " + dir);
            LOG.info("#" + "include <...> search starts here:");
            for (String dir : pp.getSystemIncludePath())
                LOG.info("  " + dir);
            LOG.info("End of search list.");
        }

        try {
            Result result = new Result();
            result.preprocessor = pp;
            pp.collector = new ActionCollectorImpl(pp, pp.inputs);
            for (;;) {
                TokenS tok = pp.token();
                if (tok == null)
                    break;
                if (tok.token.getType() == Token.EOF)
                    break;
                result.produced.add(tok);
            }
            result.original = ((ActionCollectorImpl)pp.collector).original;
            result.actions = ((ActionCollectorImpl)pp.collector).actions;
            pp.collector = new ActionCollector();
            return result;
        } catch (Exception e) {
            StringBuilder buf = new StringBuilder("Preprocessor failed:\n");
            Source s = pp.getSource();
            while (s != null) {
                buf.append(" -> ").append(s).append("\n");
                s = s.getParent();
            }
            LOG.error(buf.toString(), e);
        }
        return null;
    }

    private static void version(@Nonnull PrintStream out) {
//        BuildMetadata metadata = BuildMetadata.getInstance();
//        out.println("Anarres Java C Preprocessor version " + metadata.getVersion() + " change-id " + metadata.getChangeId());
//        out.println("Copyright (C) 2007-2015 Shevek (http://www.anarres.org/).");
//        out.println("This is free software; see the source for copying conditions.  There is NO");
//        out.println("warranty; not even for MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.");
    }
}
