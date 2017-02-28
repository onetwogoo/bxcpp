package org.anarres.cpp;

import org.pcollections.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class TargetState {
    public Environment environment;
    public PStack<Token> skipped;
    public PStack<TokenS> rest;

    public TargetState(Environment environment, PStack<Token> skipped, PStack<TokenS> rest) {
        this.environment = environment;
        this.skipped = skipped;
        this.rest = rest;
    }
}

class BackResult {
    public PSequence<PSequence<TokenS>> originalChanges;
    public PStack<TokenS> rightTokens;

    public BackResult(PSequence<PSequence<TokenS>> originalChanges, PStack<TokenS> rightTokens) {
        this.originalChanges = originalChanges;
        this.rightTokens = rightTokens;
    }
}

public class Backward {
    private final Strategy[] strategies = new Strategy[]{new PreserveRoot(), new CancelRoot(), new CancelAll()};
    private final Preprocessor pp;

    public Backward(Preprocessor pp) {
        this.pp = pp;
    }

    @Nullable
    public PSequence<PSequence<TokenS>> backward(PSequence<PSequence<TokenS>> changes, List<Action> actions) {
        PStack<PSequence<TokenS>> leftChanges = reverse(changes); // Changes on the left in reversed order
        PStack<PSequence<TokenS>> rightChanges = Empty.stack();

        // rightTokens is always flattened rightChanges
        PStack<TokenS> rightTokens = Empty.stack();

        List<TargetState> targetStates = new ArrayList<>();
        targetStates.add(new TargetState(null, Empty.stack(), Empty.stack()));

        for (int i = actions.size() - 1; i >= 0; i--) {
            Action action = actions.get(i);

            final int lenProcessed = action.processed().size();
            final int lenSkipped = action.skipped().size();
            PSequence<PSequence<TokenS>> processedChanges = rightChanges.subList(0, lenProcessed);
            PStack<PSequence<TokenS>> restChanges = rightChanges.subList(lenProcessed);
            PSequence<PSequence<TokenS>> skippedChanges = reverse(leftChanges.subList(0, lenSkipped));
            leftChanges = leftChanges.subList(lenSkipped);
            PStack<TokenS> restTokens = rightTokens.subList(flatten(processedChanges).size());

            BackResult backResult = back(action, skippedChanges, processedChanges, targetStates, restTokens);
            if (backResult == null) {
                return null;
            }
            rightTokens = backResult.rightTokens;
            rightChanges = concat(skippedChanges, concat(backResult.originalChanges, restChanges));
        }

        assert leftChanges.isEmpty();
        return rightChanges;
    }

    private BackResult back(
            Action action,
            PSequence<PSequence<TokenS>> skippedChanges,
            PSequence<PSequence<TokenS>> processedChanges,
            List<TargetState> targetStates,
            PStack<TokenS> restTokens) {

        if (!skippedChanges.isEmpty()) {
            List<Token> skippedTokens = new ArrayList<>();
            for (PSequence<TokenS> change: skippedChanges) {
                for (TokenS tokenS: change) {
                    skippedTokens.add(tokenS.token);
                }
            }
            for (TargetState state: targetStates) {
                state.skipped = concat(skippedTokens, state.skipped);
            }
        }

        for (Strategy strategy : strategies) {
            PSequence<PSequence<TokenS>> originalChanges;
            if (action instanceof Skip) {
                originalChanges = Empty.vector();
            } else if (action instanceof Replace) {
                Replace replace = (Replace)action;
                PVector<PSequence<TokenS>> withoutDisabled = Empty.vector();
                for (PSequence<TokenS> change: processedChanges) {
                    PVector<TokenS> newChange = Empty.vector();
                    for (TokenS tokenS: change) {
                        newChange = newChange.plus(new TokenS(tokenS.token, minusEach(tokenS.disables, replace.disables)));
                    }
                    withoutDisabled = withoutDisabled.plus(newChange);
                }
                originalChanges = strategy.back(replace, withoutDisabled);
            } else {
                throw new AssertionError("Unknown action type");
            }

            PStack<TokenS> rightTokens = concat(flatten(skippedChanges), concat(flatten(originalChanges), restTokens));
            if (tryForward(action.beforeEnv, rightTokens, targetStates)) {
                targetStates.add(0, new TargetState(action.beforeEnv, Empty.stack(), rightTokens));
                return new BackResult(originalChanges, rightTokens);
            }
        }
        return null;
    }

    public boolean tryForward(Environment env, PStack<TokenS> tokens, List<TargetState> targetStates) {
        throw new UnsupportedOperationException("Not implemented");
    }

    interface Strategy {
        @Nullable
        PSequence<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges);
    }

    class CancelAll implements Strategy {
        @Override
        @Nonnull
        public PSequence<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges) {
            return Backward.makeReplaceChanges(replace.original, flatten(processedChanges));
        }
    }

    class CancelRoot implements Strategy {
        @Nullable
        @Override
        public PSequence<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges) {
            PVector<TokenS> backed = Empty.vector();
            for (MapSeg seg: replace.mapping) {
                PSequence<PSequence<TokenS>> currentProcessedChanges = processedChanges.subList(0, seg.processed().size());
                processedChanges = processedChanges.subList(seg.processed().size(), processedChanges.size());

                if (seg instanceof New) {
                    backed = backed.plusAll(Backward.flatten(currentProcessedChanges));
                } else if (seg instanceof Sub) {
                    Sub sub = (Sub) seg;

                    List<TokenS> seq = Backward.flatten(currentProcessedChanges);
                    TargetState targetState = new TargetState(null, ConsPStack.from(stripS(seq)), Empty.stack());
                    if (tryForward(replace.beforeEnv, ConsPStack.from(seq), Collections.singletonList(targetState))) {
                        PSequence<PSequence<TokenS>> currentOriginalChanges = backward(currentProcessedChanges, sub.actions);
                        if (currentOriginalChanges == null) {
                            return null;
                        }
                        backed = backed.plusAll(Backward.flatten(currentOriginalChanges));
                    } else {
                        // There is tricky part that is different between forward once and twice.
                        backed = backed.plusAll(Backward.flatten(currentProcessedChanges));
                    }
                } else {
                    assert false: "Unknown mapseg";
                }
            }
            return Backward.makeReplaceChanges(replace.original, backed);
        }
    }

    class PreserveRoot implements Strategy {
        @Nullable
        @Override
        public PSequence<PSequence<TokenS>> back(Replace replace, PSequence<PSequence<TokenS>> processedChanges) {
            List<PSequence<TokenS>> originalChanges = new ArrayList<>(Collections.nCopies(replace.original.size(), null));

            for (MapSeg seg : replace.mapping) {
                PSequence<PSequence<TokenS>> currentProcessedChanges = processedChanges.subList(0, seg.processed().size());
                processedChanges = processedChanges.subList(seg.processed().size(), processedChanges.size());

                if (seg instanceof New) {
                    if (!currentProcessedChanges.equals(seg.processed())) {
                        return null;
                    }
                } else if (seg instanceof Sub) {
                    Sub sub = (Sub) seg;
                    PSequence<PSequence<TokenS>> currentOriginalChanges = backward(currentProcessedChanges, sub.actions);
                    if (currentOriginalChanges == null) {
                        return null;
                    }
                    assert currentOriginalChanges.size() == sub.indicies.size();

                    for (int i = 0; i < sub.indicies.size(); i++) {
                        int index = sub.indicies.get(i);
                        PSequence<TokenS> change = currentOriginalChanges.get(i);
                        PSequence<TokenS> old = originalChanges.get(index);
                        if (old == null) {
                            originalChanges.set(index, change);
                        } else if (!old.equals(change)) {
                            return null;
                        }
                    }
                } else {
                    assert false : "Unexpected seg type";
                }
            }

            for (int i = 0; i < originalChanges.size(); i++) {
                if (originalChanges.get(i) == null) {
                    originalChanges.set(i, TreePVector.singleton(replace.original.get(i)));
                }
            }
            return TreePVector.from(originalChanges);
        }
    }

    public static <E> PBag<E> minusEach(PBag<E> bag, PSet<E> set) {
        for (E e: set) {
            bag = bag.minus(e);
        }
        return bag;
    }

    public static <E> PStack<E> reverse(final List<E> list) {
        PStack<E> rev = ConsPStack.empty();
        for (E e : list) {
            rev = rev.plus(e);
        }
        return rev;
    }

    public static <E> PStack<E> concat(final List<E> seq, PStack<E> stack) {
        for (E e: reverse(seq)) {
            stack = stack.plus(e);
        }
        return stack;
    }

    // Efficient implementation of stack eqauls
    public static <T> boolean equals(PStack<T> stack1, PStack<T> stack2) {
        if (stack1.size() != stack2.size()) {
            return false;
        }

        while (!stack1.isEmpty()) {
            if (stack1 == stack2) {
                return true;
            }
            if (!stack1.get(0).equals(stack2.get(0))) {
                return false;
            }
            stack1 = stack1.subList(1);
            stack2 = stack2.subList(1);
        }
        return true;
    }

    public static List<TokenS> flatten(final List<PSequence<TokenS>> changes) {
        List<TokenS> result = new ArrayList<>();
        for (PSequence<TokenS> change: changes) {
            result.addAll(change);
        }
        return result;
    }

    public static List<Token> stripS(final List<TokenS> tokens) {
        List<Token> result = new ArrayList<>();
        for (TokenS tokenS: tokens) {
            result.add(tokenS.token);
        }
        return result;
    }

    public static PSequence<PSequence<TokenS>> makeReplaceChanges(final List<TokenS> from, final List<TokenS> to) {
        if (from.isEmpty() && to.isEmpty()) return Empty.vector();
        if (from.isEmpty()) {
            throw new RuntimeException("unable to change from empty to non-empty");
        }
        PStack<PSequence<TokenS>> result = Empty.stack();
        for (int i = 0; i < from.size() - 1; i++) {
            result = result.plus(Empty.vector());
        }
        return result.plus(TreePVector.from(to));
    }
}