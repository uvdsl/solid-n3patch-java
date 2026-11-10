package edu.kit.aifb.solid.n3patch.guards;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.modify.request.UpdateModify;
import org.apache.jena.update.UpdateRequest;

public class N3DeletePresenceGuard {

    public void check(UpdateRequest patch, Binding binding, Dataset dataset) {

        Model target = dataset.getDefaultModel();
        Model required = ModelFactory.createDefaultModel();
        UpdateModify update = (UpdateModify) patch.getOperations().get(0);

        // We have to do the following manual construction.
        // We cannot use a simple CONSTRUCT query here because
        // CONSTRUCT silently drops malformed triples and the guard passes vacuously.
        for (Quad quad : update.getDeleteAcc().getQuads()) {
            Triple triple = quad.asTriple();
            Node subject = substitute(triple.getSubject(), binding);
            Node predicate = substitute(triple.getPredicate(), binding);
            Node object = substitute(triple.getObject(), binding);

            // just to check for regular RDF:
            // Jena’s update execution can silently discard instantiated triples
            // with literal subjects or non-IRI predicates.
            // Allowing them could report success without actually applying the change.
            if (!(subject.isURI()
                    || subject.isBlank())
                    || !predicate.isURI()
                    || !(object.isURI()
                            || object.isBlank()
                            || object.isLiteral())) {
                throw new N3ConflictException("Delete-set cannot be instantiated");
            }
            required.getGraph().add(
                    Triple.create(subject, predicate, object));
        }

        // CHECK 2: does the triples to delete actually exist?
        if (!target.containsAll(required)) {
            throw new N3ConflictException("Delete-set not fully present");
        }

    }

    private static Node substitute(Node node, Binding binding) {
        if (!node.isVariable()) {
            return node;
        }
        Node value = binding.get(Var.alloc(node));
        if (value == null) {
            throw new N3ConflictException("Unbound delete variable: " + node);
        }
        return value;
    }

}
