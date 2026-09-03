package com.ultikits.ultitools.abstracts.command.validation;

import com.ultikits.ultitools.abstracts.command.CommandContext;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages a chain of validators that are executed in order.
 * Thread-safe implementation supporting dynamic validator registration.
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public final class ValidatorChain {

    private final List<CommandValidator> validators = new CopyOnWriteArrayList<>();
    private volatile boolean sorted = true;

    /**
     * Creates an empty validator chain.
     */
    public ValidatorChain() {
    }

    /**
     * Creates a validator chain with the specified validators.
     *
     * @param validators the validators to add
     */
    public ValidatorChain(Collection<CommandValidator> validators) {
        this.validators.addAll(validators);
        sort();
    }

    /**
     * Adds a validator to the chain.
     *
     * @param validator the validator to add
     * @return this chain for fluent chaining
     */
    public ValidatorChain addValidator(CommandValidator validator) {
        Objects.requireNonNull(validator, "Validator cannot be null");
        validators.add(validator);
        sorted = false;
        return this;
    }

    /**
     * Removes a validator from the chain.
     *
     * @param validator the validator to remove
     * @return this chain for fluent chaining
     */
    public ValidatorChain removeValidator(CommandValidator validator) {
        validators.remove(validator);
        return this;
    }

    /**
     * Removes all validators of the specified type.
     *
     * @param validatorClass the validator class to remove
     * @return this chain for fluent chaining
     */
    public ValidatorChain removeValidators(Class<? extends CommandValidator> validatorClass) {
        validators.removeIf(v -> validatorClass.isInstance(v));
        return this;
    }

    /**
     * Clears all validators from the chain.
     *
     * @return this chain for fluent chaining
     */
    public ValidatorChain clear() {
        validators.clear();
        return this;
    }

    /**
     * Validates the context through all validators in the chain.
     * Stops at the first failure.
     *
     * @param context the command context to validate
     * @return the result of the validation
     */
    public ChainValidationResult validate(CommandContext context) {
        ensureSorted();

        List<CommandValidator.ValidationResult> results = new ArrayList<>();
        List<CommandValidator> passedValidators = new ArrayList<>();

        for (CommandValidator validator : validators) {
            if (!validator.shouldValidate(context)) {
                continue;
            }

            CommandValidator.ValidationResult result = validator.validate(context);
            results.add(result);

            if (!result.isValid()) {
                return ChainValidationResult.failure(validator, result, results, passedValidators);
            }

            passedValidators.add(validator);
        }

        return ChainValidationResult.success(results, passedValidators);
    }

    /**
     * Validates the context through all validators, collecting all failures.
     * Does not stop at the first failure.
     *
     * @param context the command context to validate
     * @return the result containing all validation results
     */
    public ChainValidationResult validateAll(CommandContext context) {
        ensureSorted();

        List<CommandValidator.ValidationResult> results = new ArrayList<>();
        List<CommandValidator> failedValidators = new ArrayList<>();
        List<CommandValidator> passedValidators = new ArrayList<>();

        for (CommandValidator validator : validators) {
            if (!validator.shouldValidate(context)) {
                continue;
            }

            CommandValidator.ValidationResult result = validator.validate(context);
            results.add(result);

            if (!result.isValid()) {
                failedValidators.add(validator);
            } else {
                passedValidators.add(validator);
            }
        }

        if (failedValidators.isEmpty()) {
            return ChainValidationResult.success(results, passedValidators);
        } else {
            return ChainValidationResult.multipleFailures(failedValidators, results, passedValidators);
        }
    }

    /**
     * Gets the number of validators in the chain.
     *
     * @return the number of validators
     */
    public int size() {
        return validators.size();
    }

    /**
     * Checks if the chain is empty.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return validators.isEmpty();
    }

    /**
     * Gets an unmodifiable view of the validators.
     *
     * @return unmodifiable list of validators
     */
    public List<CommandValidator> getValidators() {
        ensureSorted();
        return Collections.unmodifiableList(validators);
    }

    private void ensureSorted() {
        if (!sorted) {
            sort();
        }
    }

    private void sort() {
        validators.sort(Comparator.comparingInt(CommandValidator::getOrder));
        sorted = true;
    }

    /**
     * Result of chain validation.
     */
    public static final class ChainValidationResult {
        private final boolean valid;
        private final CommandValidator failedValidator;
        private final List<CommandValidator> allFailedValidators;
        private final CommandValidator.ValidationResult failedResult;
        private final List<CommandValidator.ValidationResult> allResults;
        private final List<CommandValidator> passedValidators;

        private ChainValidationResult(boolean valid,
                                      CommandValidator failedValidator,
                                      List<CommandValidator> allFailedValidators,
                                      CommandValidator.ValidationResult failedResult,
                                      List<CommandValidator.ValidationResult> allResults,
                                      List<CommandValidator> passedValidators) {
            this.valid = valid;
            this.failedValidator = failedValidator;
            this.allFailedValidators = allFailedValidators != null ?
                    Collections.unmodifiableList(allFailedValidators) : Collections.emptyList();
            this.failedResult = failedResult;
            this.allResults = Collections.unmodifiableList(allResults);
            this.passedValidators = passedValidators != null
                    ? Collections.unmodifiableList(new ArrayList<>(passedValidators))
                    : Collections.emptyList();
        }

        static ChainValidationResult success(List<CommandValidator.ValidationResult> allResults,
                                              List<CommandValidator> passedValidators) {
            return new ChainValidationResult(true, null, null, null, allResults, passedValidators);
        }

        static ChainValidationResult failure(CommandValidator failedValidator,
                                             CommandValidator.ValidationResult failedResult,
                                             List<CommandValidator.ValidationResult> allResults,
                                             List<CommandValidator> passedValidators) {
            return new ChainValidationResult(false, failedValidator,
                    Collections.singletonList(failedValidator), failedResult, allResults, passedValidators);
        }

        static ChainValidationResult multipleFailures(List<CommandValidator> failedValidators,
                                                      List<CommandValidator.ValidationResult> allResults,
                                                      List<CommandValidator> passedValidators) {
            return new ChainValidationResult(false,
                    failedValidators.isEmpty() ? null : failedValidators.get(0),
                    failedValidators,
                    null,
                    allResults,
                    passedValidators);
        }

        /**
         * Checks if all validations passed.
         *
         * @return true if valid
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Gets the first failed validator.
         *
         * @return the failed validator, or null if valid
         */
        public CommandValidator getFailedValidator() {
            return failedValidator;
        }

        /**
         * Gets all failed validators.
         *
         * @return list of failed validators
         */
        public List<CommandValidator> getAllFailedValidators() {
            return allFailedValidators;
        }

        /**
         * Gets the first failed result.
         *
         * @return the failed result, or null if valid
         */
        public CommandValidator.ValidationResult getFailedResult() {
            return failedResult;
        }

        /**
         * Gets all validation results.
         *
         * @return list of all results
         */
        public List<CommandValidator.ValidationResult> getAllResults() {
            return allResults;
        }

        /**
         * Gets the ordered list of validators whose {@link CommandValidator#validate(CommandContext)}
         * succeeded during this chain run. This is the single source of truth {@code executeCommand}
         * drives {@link CommandValidator#onComplete(CommandContext, boolean)} from -- a validator
         * absent from this list receives no post-action call, whether because it was skipped, never
         * reached, or failed.
         *
         * @return unmodifiable, ordered list of validators that passed during this chain run
         * @since 6.3.0
         */
        public List<CommandValidator> getPassedValidators() {
            return passedValidators;
        }

        /**
         * Gets the error message from the first failure.
         *
         * @return the error message, or null if valid
         */
        public String getErrorMessage() {
            if (failedResult != null) {
                return failedResult.getErrorMessage();
            }
            for (CommandValidator.ValidationResult result : allResults) {
                if (!result.isValid()) {
                    return result.getErrorMessage();
                }
            }
            return null;
        }
    }

    /**
     * Builder for creating validator chains.
     */
    public static final class Builder {
        private final List<CommandValidator> validators = new ArrayList<>();

        /**
         * Adds a validator to the chain.
         *
         * @param validator the validator to add
         * @return this builder
         */
        public Builder add(CommandValidator validator) {
            validators.add(Objects.requireNonNull(validator));
            return this;
        }

        /**
         * Adds all validators to the chain.
         *
         * @param validators the validators to add
         * @return this builder
         */
        public Builder addAll(Collection<CommandValidator> validators) {
            this.validators.addAll(validators);
            return this;
        }

        /**
         * Builds the validator chain.
         *
         * @return the built chain
         */
        public ValidatorChain build() {
            return new ValidatorChain(validators);
        }
    }

    /**
     * Creates a new builder for validator chains.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }
}
