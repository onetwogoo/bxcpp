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

import java.io.*;
import java.util.*;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.crypto.interfaces.PBEKey;

import org.pcollections.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.anarres.cpp.PreprocessorCommand.*;
import org.anarres.cpp.PreprocessorListener.SourceChangeEvent;
import static org.anarres.cpp.Token.*;

/**
 * A C Preprocessor.
 * The Preprocessor outputs a token stream which does not need
 * re-lexing for C or C++. Alternatively, the output text may be
 * reconstructed by concatenating the {@link Token#getText() text}
 * values of the returned {@link Token Tokens}. (See
 * {@link CppReader}, which does this.)
 */
/*
 * Source file name and line number information is conveyed by lines of the form
 *
 * # linenum filename flags
 *
 * These are called linemarkers. They are inserted as needed into
 * the output (but never within a string or character constant). They
 * mean that the following line originated in file filename at line
 * linenum. filename will never contain any non-printing characters;
 * they are replaced with octal escape sequences.
 *
 * After the file name comes zero or more flags, which are `1', `2',
 * `3', or `4'. If there are multiple flags, spaces separate them. Here
 * is what the flags mean:
 *
 * `1'
 * This indicates the start of a new file.
 * `2'
 * This indicates returning to a file (after having included another
 * file).
 * `3'
 * This indicates that the following text comes from a system header
 * file, so certain warnings should be suppressed.
 * `4'
 * This indicates that the following text should be treated as being
 * wrapped in an implicit extern "C" block.
 */
public class Preprocessor implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(Preprocessor.class);

    private static final Source INTERNAL = new Source() {
        @Override
        public TokenS token()
                throws IOException,
                LexerException {
            throw new LexerException("Cannot read from " + getName());
        }

        @Override
        public String getPath() {
            return "<internal-data>";
        }

        @Override
        public String getName() {
            return "internal data";
        }
    };
    private static final Macro __LINE__ = new Macro(INTERNAL, "__LINE__");
    private static final Macro __FILE__ = new Macro(INTERNAL, "__FILE__");
    private static final Macro __COUNTER__ = new Macro(INTERNAL, "__COUNTER__");

    public List<Source> inputs;

    /* The fundamental engine. */
    private Map<String, Macro> macros;
    private Stack<State> states;
    private Source source;

    /* Miscellaneous support. */
    private int counter;
    private Set<String> onceseenpaths = new HashSet<String>();
    //private final List<VirtualFile> includes = new ArrayList<VirtualFile>();

    /* Support junk to make it work like cpp */
    private List<String> quoteincludepath;	/* -iquote */

    private List<String> sysincludepath;		/* -I */

    private List<String> frameworkspath;
    private Set<Feature> features;
    private Set<Warning> warnings;
    private VirtualFileSystem filesystem;
    private PreprocessorListener listener;
    public ActionCollector collector;
    public boolean collectOnly = false;

    public Preprocessor() {
        this.inputs = new ArrayList<Source>();

        this.macros = new HashMap<>();
        macros.put(__LINE__.getName(), __LINE__);
        macros.put(__FILE__.getName(), __FILE__);
        macros.put(__COUNTER__.getName(), __COUNTER__);
        this.macros = Collections.unmodifiableMap(this.macros);
        this.states = new Stack<State>();
        states.push(new State());
        this.source = null;

        this.counter = 0;

        this.quoteincludepath = new ArrayList<String>();
        this.sysincludepath = new ArrayList<String>();
        this.frameworkspath = new ArrayList<String>();
        this.features = EnumSet.noneOf(Feature.class);
        this.warnings = EnumSet.noneOf(Warning.class);
        this.filesystem = new JavaFileSystem();
        this.listener = null;
    }

    Environment getCurrentState() {
        Stack<State> newStates = new Stack<State>();
        for (State s : states) {
            newStates.add(s.clone());
        }
        return new Environment(macros,
                    newStates, counter, new ArrayList<String>(onceseenpaths));
    }

    public Preprocessor(@Nonnull Source initial) {
        this();
        addInput(initial);
    }

    /** Equivalent to
     * 'new Preprocessor(new {@link FileLexerSource}(file))'
     */
    public Preprocessor(@Nonnull File file)
            throws IOException {
        this(new FileLexerSource(file));
    }

    /**
     * Sets the VirtualFileSystem used by this Preprocessor.
     */
    public void setFileSystem(@Nonnull VirtualFileSystem filesystem) {
        this.filesystem = filesystem;
    }

    /**
     * Returns the VirtualFileSystem used by this Preprocessor.
     */
    @Nonnull
    public VirtualFileSystem getFileSystem() {
        return filesystem;
    }

    /**
     * Sets the PreprocessorListener which handles events for
     * this Preprocessor.
     *
     * The listener is notified of warnings, errors and source
     * changes, amongst other things.
     */
    public void setListener(@Nonnull PreprocessorListener listener) {
        this.listener = listener;
        Source s = source;
        while (s != null) {
            // s.setListener(listener);
            s.init(this);
            s = s.getParent();
        }
    }

    /**
     * Returns the PreprocessorListener which handles events for
     * this Preprocessor.
     */
    @Nonnull
    public PreprocessorListener getListener() {
        return listener;
    }

    /**
     * Returns the feature-set for this Preprocessor.
     *
     * This set may be freely modified by user code.
     */
    @Nonnull
    public Set<Feature> getFeatures() {
        return features;
    }

    /**
     * Adds a feature to the feature-set of this Preprocessor.
     */
    public void addFeature(@Nonnull Feature f) {
        features.add(f);
    }

    /**
     * Adds features to the feature-set of this Preprocessor.
     */
    public void addFeatures(@Nonnull Collection<Feature> f) {
        features.addAll(f);
    }

    /**
     * Adds features to the feature-set of this Preprocessor.
     */
    public void addFeatures(Feature... f) {
        addFeatures(Arrays.asList(f));
    }

    /**
     * Returns true if the given feature is in
     * the feature-set of this Preprocessor.
     */
    public boolean getFeature(@Nonnull Feature f) {
        return features.contains(f);
    }

    /**
     * Returns the warning-set for this Preprocessor.
     *
     * This set may be freely modified by user code.
     */
    @Nonnull
    public Set<Warning> getWarnings() {
        return warnings;
    }

    /**
     * Adds a warning to the warning-set of this Preprocessor.
     */
    public void addWarning(@Nonnull Warning w) {
        warnings.add(w);
    }

    /**
     * Adds warnings to the warning-set of this Preprocessor.
     */
    public void addWarnings(@Nonnull Collection<Warning> w) {
        warnings.addAll(w);
    }

    /**
     * Returns true if the given warning is in
     * the warning-set of this Preprocessor.
     */
    public boolean getWarning(@Nonnull Warning w) {
        return warnings.contains(w);
    }

    /**
     * Adds input for the Preprocessor.
     *
     * Inputs are processed in the order in which they are added.
     */
    public void addInput(@Nonnull Source source) {
        source.init(this);
        inputs.add(source);
    }

    /**
     * Adds input for the Preprocessor.
     *
     * @see #addInput(Source)
     */
    public void addInput(@Nonnull File file)
            throws IOException {
        addInput(new FileLexerSource(file));
    }

    /**
     * Handles an error.
     *
     * If a PreprocessorListener is installed, it receives the
     * error. Otherwise, an exception is thrown.
     */
    protected void error(int line, int column, @Nonnull String msg)
            throws LexerException {
        if (listener != null)
            listener.handleError(source, line, column, msg);
        else
            throw new LexerException("Error at " + line + ":" + column + ": " + msg);
    }

    /**
     * Handles an error.
     *
     * If a PreprocessorListener is installed, it receives the
     * error. Otherwise, an exception is thrown.
     *
     * @see #error(int, int, String)
     */
    protected void error(@Nonnull Token tok, @Nonnull String msg)
            throws LexerException {
        error(tok.getLine(), tok.getColumn(), msg);
    }

    /**
     * Handles a warning.
     *
     * If a PreprocessorListener is installed, it receives the
     * warning. Otherwise, an exception is thrown.
     */
    protected void warning(int line, int column, @Nonnull String msg)
            throws LexerException {
        if (warnings.contains(Warning.ERROR))
            error(line, column, msg);
        else if (listener != null)
            listener.handleWarning(source, line, column, msg);
        else
            throw new LexerException("Warning at " + line + ":" + column + ": " + msg);
    }

    /**
     * Handles a warning.
     *
     * If a PreprocessorListener is installed, it receives the
     * warning. Otherwise, an exception is thrown.
     *
     * @see #warning(int, int, String)
     */
    protected void warning(@Nonnull Token tok, @Nonnull String msg)
            throws LexerException {
        warning(tok.getLine(), tok.getColumn(), msg);
    }

    /**
     * Adds a Macro to this Preprocessor.
     *
     * The given {@link Macro} object encapsulates both the name
     * and the expansion.
     *
     * @throws LexerException if the definition fails or is otherwise illegal.
     */
    public void addMacro(@Nonnull Macro m) throws LexerException {
        // System.out.println("Macro " + m);
        String name = m.getName();
        /* Already handled as a source error in macro(). */
        if ("defined".equals(name))
            throw new LexerException("Cannot redefine name 'defined'");

        Map<String,Macro> macros = new LinkedHashMap<String, Macro>(this.macros);
        macros.put(m.getName(), m);
        this.macros = Collections.unmodifiableMap(macros);
    }

    /**
     * Defines the given name as a macro.
     *
     * The String value is lexed into a token stream, which is
     * used as the macro expansion.
     *
     * @throws LexerException if the definition fails or is otherwise illegal.
     */
    public void addMacro(@Nonnull String name, @Nonnull String value)
            throws LexerException {
        try {
            Macro m = new Macro(name);
            StringLexerSource s = new StringLexerSource(value);
            for (;;) {
                TokenS tok = s.token();
                if (tok.token.getType() == EOF)
                    break;
                m.addToken(tok.token);
            }
            addMacro(m);
        } catch (IOException e) {
            throw new LexerException(e);
        }
    }

    /**
     * Defines the given name as a macro, with the value <code>1</code>.
     *
     * This is a convnience method, and is equivalent to
     * <code>addMacro(name, "1")</code>.
     *
     * @throws LexerException if the definition fails or is otherwise illegal.
     */
    public void addMacro(@Nonnull String name)
            throws LexerException {
        addMacro(name, "1");
    }

    /**
     * Sets the user include path used by this Preprocessor.
     */
    /* Note for future: Create an IncludeHandler? */
    public void setQuoteIncludePath(@Nonnull List<String> path) {
        this.quoteincludepath = path;
    }

    /**
     * Returns the user include-path of this Preprocessor.
     *
     * This list may be freely modified by user code.
     */
    @Nonnull
    public List<String> getQuoteIncludePath() {
        return quoteincludepath;
    }

    /**
     * Sets the system include path used by this Preprocessor.
     */
    /* Note for future: Create an IncludeHandler? */
    public void setSystemIncludePath(@Nonnull List<String> path) {
        this.sysincludepath = path;
    }

    /**
     * Returns the system include-path of this Preprocessor.
     *
     * This list may be freely modified by user code.
     */
    @Nonnull
    public List<String> getSystemIncludePath() {
        return sysincludepath;
    }

    /**
     * Sets the Objective-C frameworks path used by this Preprocessor.
     */
    /* Note for future: Create an IncludeHandler? */
    public void setFrameworksPath(@Nonnull List<String> path) {
        this.frameworkspath = path;
    }

    /**
     * Returns the Objective-C frameworks path used by this
     * Preprocessor.
     *
     * This list may be freely modified by user code.
     */
    @Nonnull
    public List<String> getFrameworksPath() {
        return frameworkspath;
    }

    /**
     * Returns the Map of Macros parsed during the run of this
     * Preprocessor.
     *
     * @return The {@link Map} of macros currently defined.
     */
    @Nonnull
    public Map<String, Macro> getMacros() {
        return macros;
    }

    /**
     * Returns the named macro.
     *
     * While you can modify the returned object, unexpected things
     * might happen if you do.
     *
     * @return the Macro object, or null if not found.
     */
    @CheckForNull
    public Macro getMacro(@Nonnull String name) {
        return macros.get(name);
    }

    /**
     * Returns the list of {@link VirtualFile VirtualFiles} which have been
     * included by this Preprocessor.
     *
     * This does not include any {@link Source} provided to the constructor
     * or {@link #addInput(java.io.File)} or {@link #addInput(Source)}.
     */
//    @Nonnull
//    public List<? extends VirtualFile> getIncludes() {
//        return includes;
//    }

    /* States */
    private void push_state() {
        State top = states.peek();
        states.push(new State(top));
    }

    private void pop_state()
            throws LexerException {
        State s = states.pop();
        if (states.isEmpty()) {
            error(0, 0, "#" + "endif without #" + "if");
            states.push(s);
        }
    }

    private boolean isActive() {
        State state = states.peek();
        return state.isParentActive() && state.isActive();
    }


    /* Sources */
    /**
     * Returns the top Source on the input stack.
     *
     * @see Source
     * @see #push_source(Source,boolean)
     * @see #pop_source()
     *
     * @return the top Source on the input stack.
     */
    // @CheckForNull
    protected Source getSource() {
        return source;
    }

    /**
     * Pushes a Source onto the input stack.
     *
     * @param source the new Source to push onto the top of the input stack.
     * @param autopop if true, the Source is automatically removed from the input stack at EOF.
     * @see #getSource()
     * @see #pop_source()
     */
    protected void push_source(@Nonnull Source source, boolean autopop) {
        source.init(this);
        source.setParent(this.source, autopop);
        // source.setListener(listener);
        if (listener != null)
            listener.handleSourceChange(this.source, SourceChangeEvent.SUSPEND);
        this.source = source;
        if (listener != null)
            listener.handleSourceChange(this.source, SourceChangeEvent.PUSH);
    }

    /**
     * Pops a Source from the input stack.
     *
     * @see #getSource()
     * @see #push_source(Source,boolean)
     *
     * @param linemarker TODO: currently ignored, might be a bug?
     * @throws IOException if an I/O error occurs.
     */
    @CheckForNull
    protected TokenS pop_source(boolean linemarker)
            throws IOException {
        if (listener != null)
            listener.handleSourceChange(this.source, SourceChangeEvent.POP);
        Source s = this.source;
        this.source = s.getParent();
        /* Always a noop unless called externally. */
        s.close();
        if (listener != null && this.source != null)
            listener.handleSourceChange(this.source, SourceChangeEvent.RESUME);

        Source t = getSource();
        if (getFeature(Feature.LINEMARKERS)
                && s.isNumbered()
                && t != null) {
            /* We actually want 'did the nested source
             * contain a newline token', which isNumbered()
             * approximates. This is not perfect, but works. */
            return new TokenS(line_token(t.getLine(), t.getName(), " 2"), Empty.bag());
        }

        return null;
    }

    protected void pop_source()
            throws IOException {
        pop_source(false);
    }

    @Nonnull
    private TokenS next_source() {
        if (inputs.isEmpty())
            return new TokenS(new Token(EOF), Empty.bag());
        Source s = inputs.remove(0);
        push_source(s, true);
        return new TokenS(line_token(s.getLine(), s.getName(), " 1"), Empty.bag());
    }

    /* Source tokens */
    private TokenS source_token;

    /* XXX Make this include the NL, and make all cpp directives eat
     * their own NL. */
    @Nonnull
    private Token line_token(int line, @CheckForNull String name, @Nonnull String extra) {
        StringBuilder buf = new StringBuilder();
        buf.append("#line ").append(line)
                .append(" \"");
        /* XXX This call to escape(name) is correct but ugly. */
        if (name == null)
            buf.append("<no file>");
        else
            MacroTokenSource.escape(buf, name);
        buf.append("\"").append(extra).append("\n");
        return new Token(P_LINE, line, 0, buf.toString(), null);
    }

    @Nonnull
    private TokenS source_token()
            throws IOException,
            LexerException {
        if (source_token != null) {
            TokenS tok = source_token;
            source_token = null;
            if (getFeature(Feature.DEBUG))
                LOG.debug("Returning unget token " + tok);
            collector.getToken(tok, getSource());
            return tok;
        }

        for (;;) {
            Source s = getSource();
            if (s == null) {
                TokenS t = next_source();
                if (t.token.getType() == P_LINE && !getFeature(Feature.LINEMARKERS))
                    continue;
                collector.getToken(t, getSource());
                return t;
            }
            TokenS tok = s.token();
            /* XXX Refactor with skipline() */
            if (tok.token.getType() == EOF && s.isAutopop()) {
                // System.out.println("Autopop " + s);
                TokenS mark = pop_source(true);
                if (mark != null) {
                    collector.getToken(mark, getSource());
                    return mark;
                }
                continue;
            }
            if (getFeature(Feature.DEBUG))
                LOG.debug("Returning fresh token " + tok);
            collector.getToken(tok, getSource());
            return tok;
        }
    }

    private void source_untoken(TokenS tok) {
        if (this.source_token != null)
            throw new IllegalStateException("Cannot return two tokens");
        collector.ungetToken(tok, getSource());
        this.source_token = tok;
    }

    private boolean isWhite(Token tok) {
        int type = tok.getType();
        return (type == WHITESPACE)
                || (type == CCOMMENT)
                || (type == CPPCOMMENT);
    }

    private TokenS source_token_nonwhite()
            throws IOException,
            LexerException {
        TokenS tok;
        do {
            tok = source_token();
        } while (isWhite(tok.token));
        return tok;
    }

    /**
     * Returns an NL or an EOF token.
     *
     * The metadata on the token will be correct, which is better
     * than generating a new one.
     *
     * This method can, as of recent patches, return a P_LINE token.
     */
    private TokenS source_skipline(boolean white)
            throws IOException,
            LexerException {
        // (new Exception("skipping line")).printStackTrace(System.out);
        Source s = getSource();
        TokenS tok = s.skipline(white, this);
        /* XXX Refactor with source_token() */
        if (tok.token.getType() == EOF && s.isAutopop()) {
            // System.out.println("Autopop " + s);
            TokenS mark = pop_source(true);
            if (mark != null)
                return mark;
        }
        return tok;
    }

    /* processes and expands a macro. */
    private boolean macro(Macro m, TokenS orig)
            throws IOException,
            LexerException {
        TokenS tok;
        List<Argument> args;

        PSet<String> disables = HashTreePSet.singleton(m.getName()).plusAll(orig.disables);

        // System.out.println("pp: expanding " + m);
        if (m.isFunctionLike()) {
            OPEN:
            for (;;) {
                tok = source_token();
                // System.out.println("pp: open: token is " + tok);
                switch (tok.token.getType()) {
                    case WHITESPACE:	/* XXX Really? */

                    case CCOMMENT:
                    case CPPCOMMENT:
                    case NL:
                        break;	/* continue */

                    case '(':
                        break OPEN;
                    default:
                        source_untoken(tok);
                        return false;
                }
            }

            // tok = expanded_token_nonwhite();
            tok = source_token_nonwhite();

            /* We either have, or we should have args.
             * This deals elegantly with the case that we have
             * one empty arg. */
            if (tok.token.getType() != ')' || m.getArgs() > 0) {
                args = new ArrayList<Argument>();

                Argument arg = new Argument();
                int depth = 0;
                TokenS space = null;

                ARGS:
                for (;;) {
                    // System.out.println("pp: arg: token is " + tok);
                    switch (tok.token.getType()) {
                        case EOF:
                            error(tok.token, "EOF in macro args");
                            return false;

                        case ',':
                            if (depth == 0) {
                                if (m.isVariadic()
                                        && /* We are building the last arg. */ args.size() == m.getArgs() - 1) {
                                    /* Just add the comma. */
                                    arg.addToken(tok, collector.numToken() - 1);
                                } else {
                                    args.add(arg);
                                    arg = new Argument();
                                }
                            } else {
                                arg.addToken(tok, collector.numToken() - 1);
                            }
                            space = null;
                            break;
                        case ')':
                            if (depth == 0) {
                                args.add(arg);
                                break ARGS;
                            } else {
                                depth--;
                                arg.addToken(tok, collector.numToken() - 1);
                            }
                            space = null;
                            break;
                        case '(':
                            depth++;
                            arg.addToken(tok, collector.numToken() - 1);
                            space = null;
                            break;

                        case WHITESPACE:
                        case CCOMMENT:
                        case CPPCOMMENT:
                        case NL:
                            /* Avoid duplicating spaces. */
                            space = tok;
                            break;

                        default:
                            /* Do not put space on the beginning of
                             * an argument token. */
                            if (space != null && !arg.isEmpty())
                                arg.addToken(space, collector.numToken() - 2);
                            arg.addToken(tok, collector.numToken() - 1);
                            space = null;
                            break;

                    }
                    // tok = expanded_token();
                    tok = source_token();
                }
                /* space may still be true here, thus trailing space
                 * is stripped from arguments. */

                if (args.size() != m.getArgs()) {
                    if (m.isVariadic()) {
                        if (args.size() == m.getArgs() - 1) {
                            args.add(new Argument());
                        } else {
                            error(tok.token,
                                    "variadic macro " + m.getName()
                                    + " has at least " + (m.getArgs() - 1) + " parameters "
                                    + "but given " + args.size() + " args");
                            return false;
                        }
                    } else {
                        error(tok.token,
                                "macro " + m.getName()
                                + " has " + m.getArgs() + " parameters "
                                + "but given " + args.size() + " args");
                        /* We could replay the arg tokens, but I 
                         * note that GNU cpp does exactly what we do,
                         * i.e. output the macro name and chew the args.
                         */
                        return false;
                    }
                }

                for (Argument a : args) {
                    ActionCollector currentCollector =collector;
                    collector = new ActionCollector(this, Collections.emptyList());
                    a.expand(this);
                    a.actions = collector.actions;
                    collector = currentCollector;
                }

                // System.out.println("Macro " + m + " args " + args);
            } else {
                /* nargs == 0 and we (correctly) got () */
                args = Collections.emptyList();
            }

        } else {
            /* Macro without args. */
            args = Collections.emptyList();
        }

        if (m == __LINE__) {
            TokenS[] tokens = new TokenS[]{
                    new TokenS(
                            new Token(NUMBER, orig.token.getLine(), orig.token.getColumn(),
                                    Integer.toString(orig.token.getLine()),
                                    new NumericValue(10, Integer.toString(orig.token.getLine()))),
                            HashTreePBag.from(disables))
            };
            collector.replaceWithNewTokens(Arrays.asList(tokens[0].token), disables);
            push_source(new FixedTokenSource(tokens), true);
        } else if (m == __FILE__) {
            StringBuilder buf = new StringBuilder("\"");
            String name = getSource().getName();
            if (name == null)
                name = "<no file>";
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                switch (c) {
                    case '\\':
                        buf.append("\\\\");
                        break;
                    case '"':
                        buf.append("\\\"");
                        break;
                    default:
                        buf.append(c);
                        break;
                }
            }
            buf.append("\"");
            String text = buf.toString();

            TokenS[] tokens = new TokenS[]{
                    new TokenS(
                            new Token(STRING, orig.token.getLine(), orig.token.getColumn(), text, text),
                            HashTreePBag.from(disables))
            };
            collector.replaceWithNewTokens(Arrays.asList(tokens[0].token), disables);
            push_source(new FixedTokenSource(tokens), true);
        } else if (m == __COUNTER__) {
            /* This could equivalently have been done by adding
             * a special Macro subclass which overrides getTokens(). */
            int value = this.counter++;
            TokenS[] tokens = new TokenS[]{
                    new TokenS(
                            new Token(NUMBER, orig.token.getLine(), orig.token.getColumn(), Integer.toString(value),
                                    new NumericValue(10, Integer.toString(value))),
                            HashTreePBag.from(disables))
            };
            collector.replaceWithNewTokens(Arrays.asList(tokens[0].token), disables);
            push_source(new FixedTokenSource(tokens), true);
        } else {
            List<MapSeg> mapping = new ArrayList<MapSeg>();
            MacroTokenSource macroTokenSource = new MacroTokenSource(m, args, mapping, disables);
            collector.replaceWithMapping(mapping, disables);
            push_source(macroTokenSource, true);
        }

        return true;
    }

    /**
     * Expands an argument.
     */
    /* I'd rather this were done lazily, but doing so breaks spec. */
    @Nonnull
    /* pp */ List<TokenS> expand(@Nonnull List<TokenS> arg)
            throws IOException,
            LexerException {
        List<TokenS> expansion = new ArrayList<TokenS>();
        TokenS space = null;
        int deleteSpaceActionIndex = -1;

        push_source(new FixedTokenSource(arg), false);

        EXPANSION:
        for (;;) {
            TokenS tok = expanded_token();
            switch (tok.token.getType()) {
                case EOF:
                    break EXPANSION;

                case WHITESPACE:
                case CCOMMENT:
                case CPPCOMMENT:
                    space = tok;
                    deleteSpaceActionIndex = collector.delete();
                    break;

                default:
                    if (space != null && !expansion.isEmpty()) {
                        expansion.add(space);
                        collector.revert(deleteSpaceActionIndex, new Skip(space));
                    }
                    expansion.add(tok);
                    space = null;
                    collector.skipLast();
                    break;
            }
        }

        // Always returns null.
        pop_source(false);

        return expansion;
    }

    /* processes a #define directive */
    private TokenS define()
            throws IOException,
            LexerException {
        TokenS tok = source_token_nonwhite();
        if (tok.token.getType() != IDENTIFIER) {
            error(tok.token, "Expected identifier");
            return source_skipline(false);
        }
        /* if predefined */

        String name = tok.token.getText();
        if ("defined".equals(name)) {
            error(tok.token, "Cannot redefine name 'defined'");
            return source_skipline(false);
        }

        Macro m = new Macro(getSource(), name);
        List<String> args;

        tok = source_token();
        if (tok.token.getType() == '(') {
            tok = source_token_nonwhite();
            if (tok.token.getType() != ')') {
                args = new ArrayList<String>();
                ARGS:
                for (;;) {
                    switch (tok.token.getType()) {
                        case IDENTIFIER:
                            args.add(tok.token.getText());
                            break;
                        case ELLIPSIS:
                            // Unnamed Variadic macro
                            args.add("__VA_ARGS__");
                            // We just named the ellipsis, but we unget the token
                            // to allow the ELLIPSIS handling below to process it.
                            source_untoken(tok);
                            break;
                        case NL:
                        case EOF:
                            error(tok.token,
                                    "Unterminated macro parameter list");
                            return tok;
                        default:
                            error(tok.token,
                                    "error in macro parameters: "
                                    + tok.token.getText());
                            return source_skipline(false);
                    }
                    tok = source_token_nonwhite();
                    switch (tok.token.getType()) {
                        case ',':
                            break;
                        case ELLIPSIS:
                            tok = source_token_nonwhite();
                            if (tok.token.getType() != ')')
                                error(tok.token,
                                        "ellipsis must be on last argument");
                            m.setVariadic(true);
                            break ARGS;
                        case ')':
                            break ARGS;

                        case NL:
                        case EOF:
                            /* Do not skip line. */
                            error(tok.token,
                                    "Unterminated macro parameters");
                            return tok;
                        default:
                            error(tok.token,
                                    "Bad token in macro parameters: "
                                    + tok.token.getText());
                            return source_skipline(false);
                    }
                    tok = source_token_nonwhite();
                }
            } else {
                assert tok.token.getType() == ')' : "Expected ')'";
                args = Collections.emptyList();
            }

            m.setArgs(args);
        } else {
            /* For searching. */
            args = Collections.emptyList();
            source_untoken(tok);
        }

        /* Get an expansion for the macro, using indexOf. */
        boolean space = false;
        boolean paste = false;
        int idx;

        /* Ensure no space at start. */
        tok = source_token_nonwhite();
        EXPANSION:
        for (;;) {
            switch (tok.token.getType()) {
                case EOF:
                    break EXPANSION;
                case NL:
                    break EXPANSION;

                case CCOMMENT:
                case CPPCOMMENT:
                /* XXX This is where we implement GNU's cpp -CC. */
                // break;
                case WHITESPACE:
                    if (!paste)
                        space = true;
                    break;

                /* Paste. */
                case PASTE:
                    space = false;
                    paste = true;
                    m.addPaste(new Token(M_PASTE,
                            tok.token.getLine(), tok.token.getColumn(),
                            "#" + "#", null));
                    break;

                /* Stringify. */
                case '#':
                    if (space)
                        m.addToken(Token.space);
                    space = false;
                    TokenS la = source_token_nonwhite();
                    if (la.token.getType() == IDENTIFIER
                            && ((idx = args.indexOf(la.token.getText())) != -1)) {
                        m.addToken(new Token(M_STRING,
                                la.token.getLine(), la.token.getColumn(),
                                "#" + la.token.getText(),
                                Integer.valueOf(idx)));
                    } else {
                        m.addToken(tok.token);
                        /* Allow for special processing. */
                        source_untoken(la);
                    }
                    break;

                case IDENTIFIER:
                    if (space)
                        m.addToken(Token.space);
                    space = false;
                    paste = false;
                    idx = args.indexOf(tok.token.getText());
                    if (idx == -1)
                        m.addToken(tok.token);
                    else
                        m.addToken(new Token(M_ARG,
                                tok.token.getLine(), tok.token.getColumn(),
                                tok.token.getText(),
                                Integer.valueOf(idx)));
                    break;

                default:
                    if (space)
                        m.addToken(Token.space);
                    space = false;
                    paste = false;
                    m.addToken(tok.token);
                    break;
            }
            tok = source_token();
        }

        if (getFeature(Feature.DEBUG))
            LOG.debug("Defined macro " + m);
        addMacro(m);

        return tok;	/* NL or EOF. */

    }

    @Nonnull
    private TokenS undef()
            throws IOException,
            LexerException {
        TokenS tok = source_token_nonwhite();
        if (tok.token.getType() != IDENTIFIER) {
            error(tok.token,
                    "Expected identifier, not " + tok.token.getText());
            if (tok.token.getType() == NL || tok.token.getType() == EOF)
                return tok;
        } else {
            Macro m = getMacro(tok.token.getText());
            if (m != null) {
                /* XXX error if predefined */
                HashMap<String, Macro> macros = new HashMap<>(this.macros);
                macros.remove(m.getName());
                this.macros = Collections.unmodifiableMap(macros);
            }
        }
        return source_skipline(true);
    }

    /**
     * Attempts to include the given file.
     *
     * User code may override this method to implement a virtual
     * file system.
     *
     * @param file The VirtualFile to attempt to include.
     * @return true if the file was successfully included, false otherwise.
     * @throws IOException if an I/O error occurs.
     */
    protected boolean include(@Nonnull VirtualFile file, List<Token> producedTokens)
            throws IOException {
        // System.out.println("Try to include " + ((File)file).getAbsolutePath());
        if (!file.isFile())
            return false;
        if (getFeature(Feature.DEBUG))
            LOG.debug("pp: including " + file);
        FileLexerSource fileLexerSource = (FileLexerSource)file.getSource();
        fileLexerSource.producedTokens = producedTokens;

        push_source(fileLexerSource, true);
        return true;
    }

    /**
     * Attempts to include a file from an include path, by name.
     *
     * @param path The list of virtual directories to search for the given name.
     * @param name The name of the file to attempt to include.
     * @return true if the file was successfully included, false otherwise.
     * @throws IOException if an I/O error occurs.
     */
    protected boolean include(@Nonnull Iterable<String> path, @Nonnull String name, List<Token> producedTokens)
            throws IOException {
        for (String dir : path) {
            VirtualFile file = getFileSystem().getFile(dir, name);
            if (include(file, producedTokens))
                return true;
        }
        return false;
    }

    /**
     * Handles an include directive.
     *
     * @throws IOException if an I/O error occurs.
     * @throws LexerException if the include fails, and the error handler is fatal.
     */
    private void include(
            @CheckForNull String parent, int line,
            @Nonnull String name, boolean quoted, boolean next, List<Token> producedTokens)
            throws IOException,
            LexerException {
        if (name.startsWith("/")) {
            VirtualFile file = filesystem.getFile(name);
            if (include(file,producedTokens))
                return;
            StringBuilder buf = new StringBuilder();
            buf.append("File not found: ").append(name);
            error(line, 0, buf.toString());
            return;
        }

        VirtualFile pdir = null;
        if (quoted) {
            if (parent != null) {
                VirtualFile pfile = filesystem.getFile(parent);
                pdir = pfile.getParentFile();
            }
            if (pdir != null) {
                VirtualFile ifile = pdir.getChildFile(name);
                if (include(ifile,producedTokens))
                    return;
            }
            if (include(quoteincludepath, name,producedTokens))
                return;
        } else {
            int idx = name.indexOf('/');
            if (idx != -1) {
                String frameworkName = name.substring(0, idx);
                String headerName = name.substring(idx + 1);
                String headerPath = frameworkName + ".framework/Headers/" + headerName;
                if (include(frameworkspath, headerPath,producedTokens))
                    return;
            }
        }

        if (include(sysincludepath, name,producedTokens))
            return;

        StringBuilder buf = new StringBuilder();
        buf.append("File not found: ").append(name);
        buf.append(" in");
        if (quoted) {
            buf.append(" .").append('(').append(pdir).append(')');
            for (String dir : quoteincludepath)
                buf.append(" ").append(dir);
        }
        for (String dir : sysincludepath)
            buf.append(" ").append(dir);
        error(line, 0, buf.toString());
    }

    @Nonnull
    private TokenS include(boolean next)
            throws IOException,
            LexerException {
        LexerSource lexer = (LexerSource) source;
        try {
            lexer.setInclude(true);
            TokenS tok = token_nonwhite();

            String name;
            boolean quoted;

            if (tok.token.getType() == STRING) {
                /* XXX Use the original text, not the value.
                 * Backslashes must not be treated as escapes here. */
                StringBuilder buf = new StringBuilder((String) tok.token.getValue());
                HEADER:
                for (;;) {
                    tok = token_nonwhite();
                    switch (tok.token.getType()) {
                        case STRING:
                            buf.append((String) tok.token.getValue());
                            break;
                        case NL:
                        case EOF:
                            break HEADER;
                        default:
                            warning(tok.token,
                                    "Unexpected token on #" + "include line");
                            TokenS ret = source_skipline(false);
                            collector.skipLast();
                            return ret;
                    }
                }
                name = buf.toString();
                quoted = true;
            } else if (tok.token.getType() == HEADER) {
                name = (String) tok.token.getValue();
                quoted = false;
                tok = source_skipline(true);
            } else {
                error(tok.token,
                        "Expected string or header, not " + tok.token.getText());
                switch (tok.token.getType()) {
                    case NL:
                    case EOF:
                        collector.skipLast();
                        return tok;
                    default:
                        /* Only if not a NL or EOF already. */
                        TokenS ret = source_skipline(false);
                        collector.skipLast();
                        return ret;
                }
            }

            /* Do the inclusion. */
            List<Token> producedTokens = new ArrayList<>();
            include(source.getPath(), tok.token.getLine(), name, quoted, next, producedTokens);
            collector.replaceWithNewTokens(producedTokens, Empty.set());

            /* 'tok' is the 'nl' after the include. We use it after the
             * #line directive. */
            if (getFeature(Feature.LINEMARKERS))
                return new TokenS(line_token(1, source.getName(), " 1"), Empty.bag());

            // If a.h is x y z, it actually replaces #include <a.h>\n with \nx y z.
            // So we prepend the \n at the beginning of producedTokens
            producedTokens.add(tok.token);
            collector.directInsert(new Skip(tok));
            return tok;
        } finally {
            lexer.setInclude(false);
        }
    }

    protected void pragma_once(@Nonnull Token name)
            throws IOException, LexerException {
        Source s = this.source;
        if (!onceseenpaths.add(s.getPath())) {
            TokenS mark = pop_source(true);
            // FixedTokenSource should never generate a linemarker on exit.
            if (mark != null)
                push_source(new FixedTokenSource(Arrays.asList(mark)), true);
        }
    }

    protected void pragma(@Nonnull Token name, @Nonnull List<Token> value)
            throws IOException,
            LexerException {
        if (getFeature(Feature.PRAGMA_ONCE)) {
            if ("once".equals(name.getText())) {
                pragma_once(name);
                return;
            }
        }
        warning(name, "Unknown #" + "pragma: " + name.getText());
    }

    @Nonnull
    private TokenS pragma()
            throws IOException,
            LexerException {
        TokenS name;

        NAME:
        for (;;) {
            TokenS tok = source_token();
            switch (tok.token.getType()) {
                case EOF:
                    /* There ought to be a newline before EOF.
                     * At least, in any skipline context. */
                    /* XXX Are we sure about this? */
                    warning(tok.token,
                            "End of file in #" + "pragma");
                    return tok;
                case NL:
                    /* This may contain one or more newlines. */
                    warning(tok.token,
                            "Empty #" + "pragma");
                    return tok;
                case CCOMMENT:
                case CPPCOMMENT:
                case WHITESPACE:
                    continue NAME;
                case IDENTIFIER:
                    name = tok;
                    break NAME;
                default:
                    warning(tok.token,
                            "Illegal #" + "pragma " + tok.token.getText());
                    return source_skipline(false);
            }
        }

        TokenS tok;
        List<Token> value = new ArrayList<Token>();
        VALUE:
        for (;;) {
            tok = source_token();
            switch (tok.token.getType()) {
                case EOF:
                    /* There ought to be a newline before EOF.
                     * At least, in any skipline context. */
                    /* XXX Are we sure about this? */
                    warning(tok.token,
                            "End of file in #" + "pragma");
                    break VALUE;
                case NL:
                    /* This may contain one or more newlines. */
                    break VALUE;
                case CCOMMENT:
                case CPPCOMMENT:
                    break;
                case WHITESPACE:
                    value.add(tok.token);
                    break;
                default:
                    value.add(tok.token);
                    break;
            }
        }

        pragma(name.token, value);

        return tok;	/* The NL. */

    }

    /* For #error and #warning. */
    private void error(@Nonnull Token pptok, boolean is_error)
            throws IOException,
            LexerException {
        StringBuilder buf = new StringBuilder();
        buf.append('#').append(pptok.getText()).append(' ');
        /* Peculiar construction to ditch first whitespace. */
        TokenS tok = source_token_nonwhite();
        ERROR:
        for (;;) {
            switch (tok.token.getType()) {
                case NL:
                case EOF:
                    break ERROR;
                default:
                    buf.append(tok.token.getText());
                    break;
            }
            tok = source_token();
        }
        if (is_error)
            error(pptok, buf.toString());
        else
            warning(pptok, buf.toString());
    }

    /* This bypasses token() for #elif expressions.
     * If we don't do this, then isActive() == false
     * causes token() to simply chew the entire input line. */
    @Nonnull
    private TokenS expanded_token()
            throws IOException,
            LexerException {
        for (;;) {
            TokenS tok = source_token();
            // System.out.println("Source token is " + tok);
            if (tok.token.getType() == IDENTIFIER) {
                Macro m = getMacro(tok.token.getText());
                if (m == null)
                    return tok;
                if (tok.disables.contains(m.getName()))
                    return tok;
                if (macro(m, tok))
                    continue;
            }
            return tok;
        }
    }

    @Nonnull
    private TokenS expanded_token_nonwhite()
            throws IOException,
            LexerException {
        TokenS tok;
        do {
            tok = expanded_token();
            // System.out.println("expanded token is " + tok);
        } while (isWhite(tok.token));
        return tok;
    }

    @CheckForNull
    private TokenS expr_token = null;

    @Nonnull
    private TokenS expr_token()
            throws IOException,
            LexerException {
        TokenS tok = expr_token;

        if (tok != null) {
            // System.out.println("ungetting");
            expr_token = null;
        } else {
            tok = expanded_token_nonwhite();
            // System.out.println("expt is " + tok);

            if (tok.token.getType() == IDENTIFIER
                    && tok.token.getText().equals("defined")) {
                TokenS la = source_token_nonwhite();
                boolean paren = false;
                if (la.token.getType() == '(') {
                    paren = true;
                    la = source_token_nonwhite();
                }

                // System.out.println("Core token is " + la);
                if (la.token.getType() != IDENTIFIER) {
                    error(la.token,
                            "defined() needs identifier, not "
                            + la.token.getText());
                    tok = new TokenS(new Token(NUMBER,
                            la.token.getLine(), la.token.getColumn(),
                            "0", new NumericValue(10, "0")), Empty.bag());
                } else if (macros.containsKey(la.token.getText())) {
                    // System.out.println("Found macro");
                    tok = new TokenS(new Token(NUMBER,
                            la.token.getLine(), la.token.getColumn(),
                            "1", new NumericValue(10, "1")), Empty.bag());
                } else {
                    // System.out.println("Not found macro");
                    tok = new TokenS(new Token(NUMBER,
                            la.token.getLine(), la.token.getColumn(),
                            "0", new NumericValue(10, "0")), Empty.bag());
                }

                if (paren) {
                    la = source_token_nonwhite();
                    if (la.token.getType() != ')') {
                        expr_untoken(la);
                        error(la.token, "Missing ) in defined(). Got " + la.token.getText());
                    }
                }
            }
        }

        // System.out.println("expr_token returns " + tok);
        return tok;
    }

    private void expr_untoken(@Nonnull TokenS tok)
            throws LexerException {
        if (expr_token != null)
            throw new InternalException(
                    "Cannot unget two expression tokens."
            );
        expr_token = tok;
    }

    private int expr_priority(@Nonnull TokenS op) {
        switch (op.token.getType()) {
            case '/':
                return 11;
            case '%':
                return 11;
            case '*':
                return 11;
            case '+':
                return 10;
            case '-':
                return 10;
            case LSH:
                return 9;
            case RSH:
                return 9;
            case '<':
                return 8;
            case '>':
                return 8;
            case LE:
                return 8;
            case GE:
                return 8;
            case EQ:
                return 7;
            case NE:
                return 7;
            case '&':
                return 6;
            case '^':
                return 5;
            case '|':
                return 4;
            case LAND:
                return 3;
            case LOR:
                return 2;
            case '?':
                return 1;
            default:
                // System.out.println("Unrecognised operator " + op);
                return 0;
        }
    }

    private long expr(int priority)
            throws IOException,
            LexerException {
        /*
         * (new Exception("expr(" + priority + ") called")).printStackTrace();
         */

        TokenS tok = expr_token();
        long lhs, rhs;

        // System.out.println("Expr lhs token is " + tok);
        switch (tok.token.getType()) {
            case '(':
                lhs = expr(0);
                tok = expr_token();
                if (tok.token.getType() != ')') {
                    expr_untoken(tok);
                    error(tok.token, "Missing ) in expression. Got " + tok.token.getText());
                    return 0;
                }
                break;

            case '~':
                lhs = ~expr(11);
                break;
            case '!':
                lhs = expr(11) == 0 ? 1 : 0;
                break;
            case '-':
                lhs = -expr(11);
                break;
            case NUMBER:
                NumericValue value = (NumericValue) tok.token.getValue();
                lhs = value.longValue();
                break;
            case CHARACTER:
                lhs = ((Character) tok.token.getValue()).charValue();
                break;
            case IDENTIFIER:
                if (warnings.contains(Warning.UNDEF))
                    warning(tok.token, "Undefined token '" + tok.token.getText()
                            + "' encountered in conditional.");
                lhs = 0;
                break;

            default:
                expr_untoken(tok);
                error(tok.token,
                        "Bad token in expression: " + tok.token.getText());
                return 0;
        }

        EXPR:
        for (;;) {
            // System.out.println("expr: lhs is " + lhs + ", pri = " + priority);
            TokenS op = expr_token();
            int pri = expr_priority(op);	/* 0 if not a binop. */

            if (pri == 0 || priority >= pri) {
                expr_untoken(op);
                break EXPR;
            }
            rhs = expr(pri);
            // System.out.println("rhs token is " + rhs);
            switch (op.token.getType()) {
                case '/':
                    if (rhs == 0) {
                        error(op.token, "Division by zero");
                        lhs = 0;
                    } else {
                        lhs = lhs / rhs;
                    }
                    break;
                case '%':
                    if (rhs == 0) {
                        error(op.token, "Modulus by zero");
                        lhs = 0;
                    } else {
                        lhs = lhs % rhs;
                    }
                    break;
                case '*':
                    lhs = lhs * rhs;
                    break;
                case '+':
                    lhs = lhs + rhs;
                    break;
                case '-':
                    lhs = lhs - rhs;
                    break;
                case '<':
                    lhs = lhs < rhs ? 1 : 0;
                    break;
                case '>':
                    lhs = lhs > rhs ? 1 : 0;
                    break;
                case '&':
                    lhs = lhs & rhs;
                    break;
                case '^':
                    lhs = lhs ^ rhs;
                    break;
                case '|':
                    lhs = lhs | rhs;
                    break;

                case LSH:
                    lhs = lhs << rhs;
                    break;
                case RSH:
                    lhs = lhs >> rhs;
                    break;
                case LE:
                    lhs = lhs <= rhs ? 1 : 0;
                    break;
                case GE:
                    lhs = lhs >= rhs ? 1 : 0;
                    break;
                case EQ:
                    lhs = lhs == rhs ? 1 : 0;
                    break;
                case NE:
                    lhs = lhs != rhs ? 1 : 0;
                    break;
                case LAND:
                    lhs = (lhs != 0) && (rhs != 0) ? 1 : 0;
                    break;
                case LOR:
                    lhs = (lhs != 0) || (rhs != 0) ? 1 : 0;
                    break;

                case '?': {
                    tok = expr_token();
                    if (tok.token.getType() != ':') {
                        expr_untoken(tok);
                        error(tok.token, "Missing : in conditional expression. Got " + tok.token.getText());
                        return 0;
                    }
                    long falseResult = expr(0);
                    lhs = (lhs != 0) ? rhs : falseResult;
                }
                break;

                default:
                    error(op.token,
                            "Unexpected operator " + op.token.getText());
                    return 0;

            }
        }

        /*
         * (new Exception("expr returning " + lhs)).printStackTrace();
         */
        // System.out.println("expr returning " + lhs);
        return lhs;
    }

    @Nonnull
    private Token toWhitespace(@Nonnull Token tok) {
        String text = tok.getText();
        int len = text.length();
        boolean cr = false;
        int nls = 0;

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            switch (c) {
                case '\r':
                    cr = true;
                    nls++;
                    break;
                case '\n':
                    if (cr) {
                        cr = false;
                        break;
                    }
                /* fallthrough */
                case '\u2028':
                case '\u2029':
                case '\u000B':
                case '\u000C':
                case '\u0085':
                    cr = false;
                    nls++;
                    break;
            }
        }

        char[] cbuf = new char[nls];
        Arrays.fill(cbuf, '\n');
        return new Token(WHITESPACE,
                tok.getLine(), tok.getColumn(),
                new String(cbuf));
    }

    @Nonnull
    private TokenS _token()
            throws IOException,
            LexerException {

        for (;;) {
            TokenS tok;
            if (!isActive()) {
                Source s = getSource();
                if (s == null) {
                    TokenS t = next_source();
                    if (t.token.getType() == P_LINE && !getFeature(Feature.LINEMARKERS))
                        continue;
                    return t;
                }

                try {
                    /* XXX Tell lexer to ignore warnings. */
                    s.setActive(false);
                    tok = source_token();
                } finally {
                    /* XXX Tell lexer to stop ignoring warnings. */
                    s.setActive(true);
                }
                switch (tok.token.getType()) {
                    case HASH:
                    case NL:
                    case EOF:
                        /* The preprocessor has to take action here. */
                        break;
                    case WHITESPACE:
                        collector.skipLast();
                        return tok;
                    case CCOMMENT:
                    case CPPCOMMENT:
                        // Patch up to preserve whitespace.
                        if (getFeature(Feature.KEEPALLCOMMENTS)) {
                            collector.skipLast();
                            return tok;
                        }
                        if (!isActive()) {
                            tok = new TokenS(toWhitespace(tok.token), Empty.bag());
                            collector.replaceWithNewTokens(Collections.singletonList(tok.token), Empty.set());
                            collector.directInsert(new Skip(tok));
                            return tok;
                        }
                        if (getFeature(Feature.KEEPCOMMENTS)) {
                            collector.skipLast();
                            return tok;
                        }
                        tok = new TokenS(toWhitespace(tok.token), Empty.bag());
                        collector.replaceWithNewTokens(Collections.singletonList(tok.token), Empty.set());
                        collector.directInsert(new Skip(tok));
                        return tok;
                    default:
                        // Return NL to preserve whitespace.
						/* XXX This might lose a comment. */
                        tok = source_skipline(false);
                        collector.skipLast();
                        return tok;
                }
            } else {
                tok = source_token();
            }

            LEX:
            switch (tok.token.getType()) {
                case EOF:
                    /* Pop the stacks. */
                    return tok;

                case WHITESPACE:
                case NL:

                case CCOMMENT:
                case CPPCOMMENT:

                case '!':
                case '%':
                case '&':
                case '(':
                case ')':
                case '*':
                case '+':
                case ',':
                case '-':
                case '/':
                case ':':
                case ';':
                case '<':
                case '=':
                case '>':
                case '?':
                case '[':
                case ']':
                case '^':
                case '{':
                case '|':
                case '}':
                case '~':
                case '.':

                /* From Olivier Chafik for Objective C? */
                case '@':
                /* The one remaining ASCII, might as well. */
                case '`':

                // case '#':
                case AND_EQ:
                case ARROW:
                case CHARACTER:
                case DEC:
                case DIV_EQ:
                case ELLIPSIS:
                case EQ:
                case GE:
                case HEADER:	/* Should only arise from include() */

                case INC:
                case LAND:
                case LE:
                case LOR:
                case LSH:
                case LSH_EQ:
                case SUB_EQ:
                case MOD_EQ:
                case MULT_EQ:
                case NE:
                case OR_EQ:
                case PLUS_EQ:
                case RANGE:
                case RSH:
                case RSH_EQ:
                case STRING:
                case SQSTRING:
                case XOR_EQ:

                case NUMBER:
                    collector.skipLast();
                    return tok;

                case IDENTIFIER:
                    Macro m = getMacro(tok.token.getText());
                    if (m == null || tok.disables.contains(m.getName()) || !macro(m, tok)) {
                        collector.skipLast();
                        return tok;
                    }
                    break;

                case P_LINE:
                    if (getFeature(Feature.LINEMARKERS)) {
                        collector.skipLast();
                        return tok;
                    }
                    collector.delete();
                    break;

                case INVALID:
                    if (getFeature(Feature.CSYNTAX))
                        error(tok.token, String.valueOf(tok.token.getValue()));
                    collector.skipLast();
                    return tok;

                default:
                    throw new InternalException("Bad token " + tok);
                // break;

                case HASH:
                    tok = source_token_nonwhite();
                    // (new Exception("here")).printStackTrace();
                    switch (tok.token.getType()) {
                        case NL:
                            collector.delete();
                            break LEX;	/* Some code has #\n */

                        case IDENTIFIER:
                            break;
                        default:
                            error(tok.token,
                                    "Preprocessor directive not a word "
                                    + tok.token.getText());
                            TokenS ret = source_skipline(false);
                            collector.skipLast();
                            return ret;
                    }
                    PreprocessorCommand ppcmd = PreprocessorCommand.forText(tok.token.getText());
                    if (ppcmd == null) {
                        error(tok.token,
                                "Unknown preprocessor directive "
                                + tok.token.getText());
                        TokenS ret = source_skipline(false);
                        collector.skipLast();
                        return ret;
                    }

                    PP:
                    switch (ppcmd) {

                        case PP_DEFINE:
                            if (!isActive()) {
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else {
                                TokenS ret = define();
                                collector.skipLast();
                                return ret;
                            }
                            // break;

                        case PP_UNDEF:
                            if (!isActive()) {
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else {
                                TokenS ret = undef();
                                collector.skipLast();
                                return ret;
                            }
                            // break;

                        case PP_INCLUDE:
                            if (!isActive()) {
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else
                                return include(false);
                            // break;
                        case PP_INCLUDE_NEXT:
                            if (!isActive()) {
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            }
                            if (!getFeature(Feature.INCLUDENEXT)) {
                                error(tok.token,
                                        "Directive include_next not enabled"
                                );
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            }
                            return include(true);
                        // break;

                        case PP_WARNING:
                        case PP_ERROR:
                            if (!isActive()) {
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else
                                error(tok.token, ppcmd == PP_ERROR);
                            collector.delete();
                            break;

                        case PP_IF:
                            push_state();
                            if (!isActive()) {
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            }
                            expr_token = null;
                            collectOnly = true;
                            states.peek().setActive(expr(0) != 0);
                            collectOnly = false;
                            tok = expr_token();	/* unget */

                            if (tok.token.getType() == NL) {
                                collector.skipLast();
                                return tok;
                            } else {
                                TokenS ret = source_skipline(true);
                                collector.skipLast();
                                return ret;
                            }
                            // break;

                        case PP_ELIF:
                            State state = states.peek();
                            if (false) {
                                /* Check for 'if' */
                                ;
                            } else if (state.sawElse()) {
                                error(tok.token,
                                        "#elif after #" + "else");
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else if (!state.isParentActive()) {
                                /* Nested in skipped 'if' */
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else if (state.isActive()) {
                                /* The 'if' part got executed. */
                                state.setParentActive(false);
                                /* This is like # else # if but with
                                 * only one # end. */
                                state.setActive(false);
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else {
                                expr_token = null;
                                collectOnly = true;
                                state.setActive(expr(0) != 0);
                                collectOnly = false;
                                tok = expr_token();	/* unget */

                                if (tok.token.getType() == NL) {
                                    collector.skipLast();
                                    return tok;
                                }
                                TokenS ret = source_skipline(true);
                                collector.skipLast();
                                return ret;
                            }
                            // break;

                        case PP_ELSE:
                            state = states.peek();
                            if (false)
								/* Check for 'if' */ ;
                            else if (state.sawElse()) {
                                error(tok.token,
                                        "#" + "else after #" + "else");
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else {
                                state.setSawElse();
                                state.setActive(!state.isActive());
                                TokenS ret = source_skipline(warnings.contains(Warning.ENDIF_LABELS));
                                collector.skipLast();
                                return ret;
                            }
                            // break;

                        case PP_IFDEF:
                            push_state();
                            if (!isActive()) {
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else {
                                tok = source_token_nonwhite();
                                // System.out.println("ifdef " + tok);
                                if (tok.token.getType() != IDENTIFIER) {
                                    error(tok.token,
                                            "Expected identifier, not "
                                                    + tok.token.getText());
                                    TokenS ret = source_skipline(false);
                                    collector.skipLast();
                                    return ret;
                                } else {
                                    String text = tok.token.getText();
                                    boolean exists
                                            = macros.containsKey(text);
                                    states.peek().setActive(exists);
                                    TokenS ret = source_skipline(true);
                                    collector.skipLast();
                                    return ret;
                                }
                            }
                            // break;

                        case PP_IFNDEF:
                            push_state();
                            if (!isActive()) {
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            } else {
                                tok = source_token_nonwhite();
                                if (tok.token.getType() != IDENTIFIER) {
                                    error(tok.token,
                                            "Expected identifier, not "
                                                    + tok.token.getText());
                                    TokenS ret = source_skipline(false);
                                    collector.skipLast();
                                    return ret;
                                } else {
                                    String text = tok.token.getText();
                                    boolean exists
                                            = macros.containsKey(text);
                                    states.peek().setActive(!exists);
                                    TokenS ret = source_skipline(true);
                                    collector.skipLast();
                                    return ret;
                                }
                            }
                            // break;

                        case PP_ENDIF:{
                            pop_state();
                            TokenS ret = source_skipline(warnings.contains(Warning.ENDIF_LABELS));
                            collector.skipLast();
                            return ret;
                    }
                        // break;

                        case PP_LINE: {
                            TokenS ret = source_skipline(false);
                            collector.skipLast();
                            return ret;
                        }
                        // break;

                        case PP_PRAGMA:
                            if (!isActive()) {
                                TokenS ret = source_skipline(false);
                                collector.skipLast();
                                return ret;
                            }
                            TokenS ret = pragma();
                            collector.skipLast();
                            return ret;
                        // break;

                        default:
                            /* Actual unknown directives are
                             * processed above. If we get here,
                             * we succeeded the map lookup but
                             * failed to handle it. Therefore,
                             * this is (unconditionally?) fatal. */
                            // if (isActive()) /* XXX Could be warning. */
                            throw new InternalException(
                                    "Internal error: Unknown directive "
                                    + tok);
                        // return source_skipline(false);
                    }

            }
        }
    }

    @Nonnull
    private TokenS token_nonwhite()
            throws IOException,
            LexerException {
        if (collectOnly) throw new Error("Nested collect only");
        collectOnly = true;
        TokenS tok;
        do {
            tok = _token();
        } while (isWhite(tok.token));
        collectOnly = false;
        return tok;
    }

    /**
     * Returns the next preprocessor token.
     *
     * @see Token
     * @return The next fully preprocessed token.
     * @throws IOException if an I/O error occurs.
     * @throws LexerException if a preprocessing error occurs.
     * @throws InternalException if an unexpected error condition arises.
     */
    @Nonnull
    public TokenS token()
            throws IOException,
            LexerException {
        TokenS tok = _token();
        if (getFeature(Feature.DEBUG))
            LOG.debug("pp: Returning " + tok);
        return tok;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();

        Source s = getSource();
        while (s != null) {
            buf.append(" -> ").append(String.valueOf(s)).append("\n");
            s = s.getParent();
        }

        Map<String, Macro> macros = new TreeMap<String, Macro>(getMacros());
        for (Macro macro : macros.values()) {
            buf.append("#").append("macro ").append(macro).append("\n");
        }

        return buf.toString();
    }

    @Override
    public void close()
            throws IOException {
        {
            Source s = source;
            while (s != null) {
                s.close();
                s = s.getParent();
            }
        }
        for (Source s : inputs) {
            s.close();
        }
    }

}
