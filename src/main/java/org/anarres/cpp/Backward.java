package org.anarres.cpp;

import org.pcollections.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

class BackResult {
    public List<PSequence<TokenS>> originalChanges;
    public FList<TokenS> rightTokens;
    public TargetStates targetStates;

    public BackResult(List<PSequence<TokenS>> originalChanges, FList<TokenS> rightTokens, TargetStates targetStates) {
        this.originalChanges = originalChanges;
        this.rightTokens = rightTokens;
        this.targetStates = targetStates;
    }
}

public class Backward {
    private final Strategy[] strategies = new Strategy[]{new PreserveRoot(), new CancelRoot(), new CancelAll()};
    private final Preprocessor pp;

    public Backward(Preprocessor pp) {
        this.pp = pp;
    }

    @Nullable
    public List<PSequence<TokenS>> backward(final List<PSequence<TokenS>> changes, final List<Action> actions) {
        FList<PSequence<TokenS>> leftChanges = FList.fromReversed(changes); // Changes on the left in reversed order
        FList<PSequence<TokenS>> rightChanges = FList.empty();

        // rightTokens is always flattened rightChanges
        FList<TokenS> rightTokens = FList.empty();

        Iterator iter = rightTokens.iterator();

        TargetStates targetStates = new TerminalState();

        for (int i = actions.size() - 1; i >= 0; i--) {
            Action action = actions.get(i);

            final int lenProcessed = action.processed().size();
            final int lenSkipped = action.skipped().size();
//            if (changes.size() == 2) {
//                System.out.println("Action:" + action);
//                System.out.println("rightChanges:" + rightChanges);
//                System.out.println("leftChanges:" + leftChanges);
//                System.out.println();
//            }
            FList<PSequence<TokenS>> processedChanges = rightChanges.subList(0, lenProcessed);
            FList<PSequence<TokenS>> restChanges = rightChanges.subList(lenProcessed);
            List<PSequence<TokenS>> skippedChanges = leftChanges.reversedSubList(0, lenSkipped);
            leftChanges = leftChanges.subList(lenSkipped);
            FList<TokenS> restTokens = rightTokens.subList(flattenSize(processedChanges));

            BackResult backResult = back(action, skippedChanges, processedChanges, targetStates, restTokens);
            if (backResult == null) {
                return null;
            }
            rightTokens = backResult.rightTokens;
            targetStates = backResult.targetStates;
            rightChanges = FList.concat(skippedChanges, FList.concat(backResult.originalChanges, restChanges));
//            System.out.println(i + " of " + actions.size());
        }

//        if (changes.size() == 2) {
//            System.out.println("Final right:" + rightChanges);
//        }

        assert leftChanges.isEmpty();
        return rightChanges;
    }

    private BackResult back(
            Action action,
            final List<PSequence<TokenS>> skippedChanges,
            FList<PSequence<TokenS>> processedChanges,
            TargetStates targetStates,
            FList<TokenS> restTokens) {

        if (!skippedChanges.isEmpty()) {
            ArrayList<Token> skippedTokens = new ArrayList<>();
            for (PSequence<TokenS> change : skippedChanges) {
                for (TokenS tokenS : change) {
                    skippedTokens.add(tokenS.token);
                }
            }
            targetStates = TargetStates.afterSkip(skippedTokens, targetStates);
        }

        for (Strategy strategy : strategies) {
            List<PSequence<TokenS>> originalChanges;
            if (action instanceof Skip) {
                originalChanges = Empty.vector();
            } else if (action instanceof Replace) {
                Replace replace = (Replace) action;
                PVector<PSequence<TokenS>> withoutDisabled = Empty.vector();
                for (PSequence<TokenS> change : processedChanges) {
                    PVector<TokenS> newChange = Empty.vector();
                    for (TokenS tokenS : change) {
                        newChange = newChange.plus(new TokenS(tokenS.token, minusEach(tokenS.disables, replace.disables)));
                    }
                    withoutDisabled = withoutDisabled.plus(newChange);
                }
                originalChanges = strategy.back(replace, withoutDisabled);
                if (originalChanges == null) {
                    continue;
                }
            } else {
                throw new AssertionError("Unknown action type");
            }

            FList<TokenS> rightTokens = FList.concat(flatten(skippedChanges), FList.concat(flatten(originalChanges), restTokens));
            if (tryForward(action.beforeEnv, rightTokens, targetStates)) {
                targetStates = new EnvAndRest(action.beforeEnv, rightTokens, targetStates);
                return new BackResult(originalChanges, rightTokens, targetStates);
            }
        }
        return null;
    }

    private boolean tryForward(Environment env, FList<TokenS> tokens, TargetStates targetStates) {
        try {
            pp.setCurrentState(env, tokens);
            int step = 0;
            for (; ; ) {
                if (tokens != null) {
//                    System.out.println("Test step " + step + " env " + env + " rest " + tokens + " on\n" + targetStates);
                    if (targetStates.matches(env, tokens)) {
//                        System.out.println("steps=" + step);
//                        System.out.println();
                        return true;
                    }
                }

                step++;

                Token token = pp.token().token;
                env = pp.getCurrentState(env);
                if (token.getType() == Token.EOF) {
                    if (tokens != null && tokens.isEmpty()) {
//                        System.out.println("Failed due to drain");
                        return false;
                    } else {
                        tokens = FList.empty();
                    }
                } else {
                    targetStates = targetStates.whenSkip(token);
                    if (targetStates == null) {
//                        System.out.println("Failed due to skip bad token " + token);
                        return false;
                    }
                    tokens = pp.getRestTokens();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    interface Strategy {
        @Nullable
        List<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges);
    }

    class CancelAll implements Strategy {
        @Override
        @Nonnull
        public List<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges) {
            return Backward.makeReplaceChanges(replace.original, flatten(processedChanges));
        }
    }

    class CancelRoot implements Strategy {
        @Nullable
        @Override
        public List<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges) {
            PVector<TokenS> backed = Empty.vector();
            for (MapSeg seg : replace.mapping) {
                PSequence<PSequence<TokenS>> currentProcessedChanges = processedChanges.subList(0, seg.processed().size());
                processedChanges = processedChanges.subList(seg.processed().size(), processedChanges.size());

                if (seg instanceof New) {
                    backed = backed.plusAll(Backward.flatten(currentProcessedChanges));
                } else if (seg instanceof Sub) {
                    Sub sub = (Sub) seg;

                    List<TokenS> seq = Backward.flatten(currentProcessedChanges);
                    TargetStates targetStates = TargetStates.afterSkip(stripS(seq), new TerminalState());
                    if (tryForward(replace.beforeEnv, FList.from(seq), targetStates)) {
                        List<PSequence<TokenS>> currentOriginalChanges = backward(currentProcessedChanges, sub.actions);
                        if (currentOriginalChanges == null) {
                            return null;
                        }
                        backed = backed.plusAll(Backward.flatten(currentOriginalChanges));
                    } else {
                        // There is tricky part that is different between forward once and twice.
                        backed = backed.plusAll(Backward.flatten(currentProcessedChanges));
                    }
                } else {
                    throw new AssertionError("Unknown mapseg");
                }
            }
            return Backward.makeReplaceChanges(replace.original, backed);
        }
    }

    class PreserveRoot implements Strategy {
        @Nullable
        @Override
        public List<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges) {
            List<PSequence<TokenS>> originalChanges = new ArrayList<>(Collections.nCopies(replace.original.size(), null));

            for (MapSeg seg : replace.mapping) {
                PSequence<PSequence<TokenS>> currentProcessedChanges = processedChanges.subList(0, seg.processed().size());
                processedChanges = processedChanges.subList(seg.processed().size(), processedChanges.size());

                if (seg instanceof New) {
                    PSequence<PSequence<TokenS>> expectedChanges = Empty.vector();
                    for (Token token : ((New) seg).tokens) {
                        expectedChanges = expectedChanges.plus(TreePVector.singleton(new TokenS(token, Empty.bag())));
                    }
                    if (!currentProcessedChanges.equals(expectedChanges)) {
                        return null;
                    }
                } else if (seg instanceof Sub) {
                    Sub sub = (Sub) seg;
                    List<PSequence<TokenS>> currentOriginalChanges = backward(currentProcessedChanges, sub.actions);
                    if (currentOriginalChanges == null) {
                        return null;
                    }
                    if (currentOriginalChanges.size() != sub.indicies.size()) {
                        throw new AssertionError();
                    }

                    int i = 0;
                    for (PSequence<TokenS> change : currentOriginalChanges) {
                        int index = sub.indicies.get(i++);

                        PSequence<TokenS> old = originalChanges.get(index);
                        if (old == null) {
                            originalChanges.set(index, change);
                        } else if (!old.equals(change)) {
                            return null;
                        }
                    }
                } else {
                    throw new AssertionError("Unexpected seg type");
                }
            }

            for (int i = 0; i < originalChanges.size(); i++) {
                if (originalChanges.get(i) == null) {
                    originalChanges.set(i, TreePVector.singleton(replace.original.get(i)));
                }
            }
            return originalChanges;
        }
    }

    public static <E> PBag<E> minusEach(PBag<E> bag, PSet<E> set) {
        for (E e : set) {
            bag = bag.minus(e);
        }
        return bag;
    }

    public static List<TokenS> flatten(final List<PSequence<TokenS>> changes) {
        List<TokenS> result = new ArrayList<>();
        for (PSequence<TokenS> change : changes) {
            result.addAll(change);
        }
        return result;
    }

    public static int flattenSize(final List<PSequence<TokenS>> changes) {
        int size = 0;
        for (PSequence<TokenS> change : changes) {
            size += change.size();
        }
        return size;
    }

    public static ArrayList<Token> stripS(final List<TokenS> tokens) {
        ArrayList<Token> result = new ArrayList<>();
        for (TokenS tokenS : tokens) {
            result.add(tokenS.token);
        }
        return result;
    }

    public static ArrayList<PSequence<TokenS>> makeReplaceChanges(final List<TokenS> from, final List<TokenS> to) {
        if (from.isEmpty() && to.isEmpty()) return new ArrayList<>();
        assert !from.isEmpty() : "Change from empty to non-empty";

        ArrayList<PSequence<TokenS>> result = new ArrayList<>();
        result.add(TreePVector.from(to));
        for (int i = 1; i < from.size(); i++) {
            result.add(Empty.vector());
        }
        return result;
    }
}