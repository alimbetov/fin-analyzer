package kz.fin.formlibrary.contract;

import java.io.Serializable;
import java.util.UUID;

/** Existing form-version UUID is the immutable contract's primary key. */
public record FormContractPolicyId(UUID formVersionId) implements Serializable {}
