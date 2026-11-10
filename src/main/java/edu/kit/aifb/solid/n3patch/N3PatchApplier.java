package edu.kit.aifb.solid.n3patch;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateRequest;

import edu.kit.aifb.solid.n3patch.guards.N3DeletePresenceGuard;
import edu.kit.aifb.solid.n3patch.guards.N3WhereMatchGuard;
import edu.kit.aifb.solid.n3patch.parsers.N3PatchTranslator;

/**
 * <i>Compare-and-Swap (CAS) note:</i>
 * A delete-set citing the prior state as constants gives the client lost-update
 * detection (replay -> 409).
 * A variable-valued delete-set recaptures the new value on replay and passes ->
 * no CAS protection.
 */
public class N3PatchApplier {

    private N3PatchTranslator translator = new N3PatchTranslator();
    private N3WhereMatchGuard whereGuard = new N3WhereMatchGuard();
    private N3DeletePresenceGuard deleteGuard = new N3DeletePresenceGuard();

    /**
     * Applies the patch to the supplied dataset's default graph.
     *
     * @param patchText the N3 patch document
     * @param targetUri the resource URI used to resolve relative IRIs
     * @param dataset   an abortable transactional dataset, such as
     *                  DatasetFactory.createTxnMem() or TDB2
     * @throws IllegalArgumentException if transactions or rollback are unsupported,
     *                                  or the caller already has a transaction open
     * @throws N3BadPatchException      if the patch is malformed or structurally
     *                                  invalid
     * @throws N3ConflictException      if either precondition check fails
     */
    public void apply(String patchText, String targetUri, Dataset dataset) {
        if (!dataset.supportsTransactions()
                || !dataset.supportsTransactionAbort()) {
            throw new IllegalArgumentException(
                    "N3PatchApplier requires an abortable transactional dataset, "
                            + "such as DatasetFactory.createTxnMem() or TDB2");
        }

        if (dataset.isInTransaction()) {
            throw new IllegalArgumentException(
                    "N3PatchApplier must own its transaction; "
                            + "call apply() without an already-open transaction");
        }

        // translate from N3Patch to SPARQL udpate query
        UpdateRequest patch = translator.translate(patchText, targetUri);

        // begin a dataset transaction for atomicity of the patch
        dataset.begin(ReadWrite.WRITE);
        try {
            // CHECK 1: does exactly one mapping per variable exist?
            Binding binding = whereGuard.check(patch, dataset);
            // CHECK 2: does the triples to delete actually exist?
            deleteGuard.check(patch, binding, dataset);
            // SPARQL UPDATE
            UpdateAction.execute(patch, dataset.asDatasetGraph());
            dataset.commit();
        } catch (RuntimeException | Error e) {
            dataset.abort();
            throw e;
        } finally {
            dataset.end();
        }
    }
}
