package edu.kit.aifb.solid.n3patch.parsers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.modify.request.UpdateModify;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.syntax.PatternVars;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
import org.apache.jena.vocabulary.RDF;

public class N3PatchTranslator {

    private static final String SOLID = "http://www.w3.org/ns/solid/terms#";
    private static final List<String> PREDICATES = List.of("where", "deletes", "inserts");

    private record FormulaInformation(int open, int close, Node placeholder) {
    }

    public UpdateRequest translate(String text, String targetUri) {
        try {

            // Jena's Turtle parser cannot accept N3 formula objects.
            // We therefore need to identify the formulae for where, inserts, and deletes.
            // The n3 patch triples the the formulas replaced with placeholders is called
            // the n3 patch "envelope".
            List<FormulaInformation> formulaInfos = findFormulas(text); // index information and place holder name
            Map<Node, String> formulaBodies = new HashMap<>(); // { placeholder node : the actual formula body }
            StringBuilder envelopeText = new StringBuilder(text); // the n3 patch envelope surrounding the formulae

            // Reverse-order replacements of the formulae with placeholders
            // preserve the original span offsets in the n3 patch envelope string.
            for (int i = formulaInfos.size() - 1; i >= 0; i--) {
                FormulaInformation formula = formulaInfos.get(i);
                formulaBodies.put(
                        formula.placeholder(),
                        text.substring(formula.open() + 1, formula.close()));
                envelopeText.replace(
                        formula.open(),
                        formula.close() + 1,
                        "<" + formula.placeholder().getURI() + ">");
            }

            // let's parse the n3 patch envelope
            Model envelope = RDFParser.fromString(envelopeText.toString())
                    .lang(Lang.TURTLE)
                    .base(targetUri)
                    .toModel();

            // check for explicit patch type
            var subjects = envelope.listSubjectsWithProperty(
                    RDF.type,
                    envelope.createResource(SOLID + "InsertDeletePatch"));
            List<Resource> patchSubjects;
            try {
                patchSubjects = subjects.toList();
            } finally {
                subjects.close();
            }
            if (patchSubjects.size() != 1) {
                throw new N3BadPatchException(
                        "Expected exactly one solid:InsertDeletePatch");
            }

            // prepare for translating to SPARQL Update
            Resource currentPatchSubject = patchSubjects.get(0);
            Map<String, String> formulae = new HashMap<>(); // { patchPredicate : formulaBody }
            Set<Node> used = new HashSet<>(); // to keep track of visited placeholder nodes while iterating predicates
            // let's now move through the patch's predicates (where, inserts, deletes)
            for (String predicate : PREDICATES) {
                var statements = currentPatchSubject.listProperties(
                        envelope.createProperty(SOLID + predicate));

                try {
                    var statementsList = statements.toList();

                    // each formula predicate is optional, but may occur at most once.
                    if (statementsList.size() > 1) {
                        throw new N3BadPatchException("Duplicate solid:" + predicate);
                    }

                    if (!statementsList.isEmpty()) {
                        Node object = statementsList.get(0).getObject().asNode(); // this is a placeholder value
                        String body = formulaBodies.get(object); // get the actual formula body (the triples)

                        if (body == null || !used.add(object)) {
                            throw new N3BadPatchException(
                                    "solid:" + predicate
                                            + " must have its own formula");
                        }

                        formulae.put(predicate, body); // to keep track of which predicate has which formula body
                    }
                } finally {
                    statements.close();
                }
            }

            // sanity check
            if (used.size() != formulaInfos.size()) {
                throw new N3BadPatchException(
                        "Formula outside the selected patch resource");
            }

            // let's translate now the predicates' formula bodies to SPARQL
            StringBuilder sparql = new StringBuilder();
            // prefixes
            envelope.getNsPrefixMap().forEach((prefix, iri) -> sparql.append("PREFIX ").append(prefix)
                    .append(": <").append(iri).append(">\n"));
            // formulae
            sparql.append("DELETE { ")
                    .append(formulae.getOrDefault("deletes", ""))
                    .append("\n} INSERT { ")
                    .append(formulae.getOrDefault("inserts", ""))
                    .append("\n} WHERE { ")
                    .append(formulae.getOrDefault("where", ""))
                    .append("\n}");

            // build SPARQL patch
            UpdateRequest request = UpdateFactory.create(sparql.toString(), targetUri);

            // sanity checks
            if (request.getOperations().size() != 1
                    || !(request.getOperations().get(0) instanceof UpdateModify update)) {
                throw new N3BadPatchException("Expected one DELETE/INSERT update");
            }
            validate(update);

            return request;
        } catch (N3BadPatchException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new N3BadPatchException("Cannot translate N3 patch", e);
        }
    }

    /**
     * This is a sanity check.
     * Validates the parsed update against the supported N3 Patch profile.
     *
     * <ul>
     * <li>WHERE contains only triple patterns and groups of triple patterns:
     * no property paths, FILTER, OPTIONAL, UNION, GRAPH, or other
     * SPARQL constructs.</li>
     * <li>DELETE and INSERT templates contain only default-graph triples.</li>
     * <li>DELETE and INSERT templates contain no explicit blank nodes.</li>
     * <li>Every variable in DELETE or INSERT also appears in WHERE.</li>
     * </ul>
     *
     * This checks structural validity only. It does not check whether WHERE
     * matches the target or whether the instantiated delete-set is present;
     * those preconditions are checked by the guards inside the write transaction.
     *
     * @param update the parsed DELETE/INSERT update
     * @throws N3BadPatchException if the update violates the supported profile
     */
    private static void validate(UpdateModify update) {
        requireTriplePattern(update.getWherePattern());
        Collection<?> whereVars = PatternVars.vars(update.getWherePattern());
        validateTemplate(update.getDeleteAcc().getQuads(), whereVars);
        validateTemplate(update.getInsertAcc().getQuads(), whereVars);
    }

    /**
     * SPARQL accepts more than this N3 Patch profile does. Restricting the
     * parsed WHERE prevents FILTER, OPTIONAL, UNION, property paths, and
     * other SPARQL constructs from becoming accidental format extensions.
     */
    private static void requireTriplePattern(Element element) {
        if (element instanceof ElementGroup group) {
            group.getElements().forEach(
                    N3PatchTranslator::requireTriplePattern);
        } else if (element instanceof ElementPathBlock block) {
            var paths = block.patternElts();

            while (paths.hasNext()) {
                if (!paths.next().isTriple()) {
                    throw new N3BadPatchException(
                            "Property paths are not N3 triple patterns");
                }
            }
        } else if (!(element instanceof ElementTriplesBlock)) {
            throw new N3BadPatchException(
                    "solid:where must contain only triple patterns");
        }
    }

    private static void validateTemplate(
            List<Quad> quads, Collection<?> whereVars) {

        for (Quad quad : quads) {

            // Jena represents templates as quads even for ordinary triples.
            // Rejecting named graphs preserves the chosen default-graph resource
            // model instead of silently enabling dataset-wide modifications.
            if (!quad.isDefaultGraph()) {
                throw new N3BadPatchException(
                        "GRAPH clauses are forbidden in formula templates");
            }

            Triple triple = quad.asTriple();

            for (Node node : List.of(
                    triple.getSubject(),
                    triple.getPredicate(),
                    triple.getObject())) {

                // SPARQL permits blank nodes in INSERT templates, but this supported
                // N3 Patch profile rejects explicit blank nodes in DELETE and INSERT,
                // see https://solidproject.org/TR/protocol#server-patch-n3-blank-nodes.
                // Variables bound to existing blank nodes by WHERE remain permitted.
                if (node.isBlank()) {
                    throw new N3BadPatchException(
                            "Blank nodes are forbidden in deletes/inserts; see https://solidproject.org/TR/protocol#server-patch-n3-blank-nodes");
                }

                // Unbound template variables can cause SPARQL to omit triples.
                // Rejecting undeclared variables here prevents successful
                // execution from silently dropping requested changes.
                if (node.isVariable()
                        && !whereVars.contains(Var.alloc(node))) {
                    throw new N3BadPatchException(
                            "Variable not declared in solid:where: " + node);
                }
            }
        }
    }

    private static List<FormulaInformation> findFormulas(String text) {
        List<FormulaInformation> formulas = new ArrayList<>();
        // internal namespace to not accidentally collide with an one from the input
        String namespace_internal = "urn:n3patch:" + UUID.randomUUID() + ":";
        // to keep track of the current opening formula bracket
        int open = -1; // index of opening bracket

        // Only formula boundaries require custom scanning:
        // Braces inside literals, IRIs, or comments are not delimiters;
        // the remainder of the syntax is left to Jena rather re-writing a parser.
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // let's now analyse the next token
            if (c == '"' || c == '\'') {
                // Literal
                i = stringEnd(text, i); // jump to the actual end of the string
            } else if (c == '<') {
                // IRI
                int end = text.indexOf('>', i + 1); // find next closing braket
                if (end < 0) {
                    throw new N3BadPatchException("Unterminated IRI");
                }
                i = end;
            } else if (c == '#') {
                // Comment
                while (i < text.length()
                        && text.charAt(i) != '\n'
                        && text.charAt(i) != '\r') {
                    i++;
                } // skip comment
            } else if (c == '{') {
                // opening a Formula
                if (open >= 0) {
                    throw new N3BadPatchException("Nested formula");
                }
                open = i; // found formula
            } else if (c == '}') {
                // closing a Formula
                if (open < 0) {
                    throw new N3BadPatchException("Unmatched closing brace");
                }

                formulas.add(new FormulaInformation(
                        open,
                        i,
                        NodeFactory.createURI(
                                namespace_internal + formulas.size())));

                open = -1; // reset current opening formula bracket index
            }
        }

        // was the last found formula closed?
        if (open >= 0) {
            throw new N3BadPatchException("Unterminated formula");
        }

        return formulas;
    }

    /**
     * Jumps to the end of the current string, given the start index.
     * 
     * Escapes and long-string delimiters affect where a literal ends.
     * Finding merely the next quote would expose literal content to brace
     * matching and reject otherwise valid patches.
     */
    private static int stringEnd(String text, int start) {
        String delimiter = String.valueOf(text.charAt(start));

        if (text.startsWith(delimiter.repeat(3), start)) {
            delimiter = delimiter.repeat(3);
        }

        for (int i = start + delimiter.length(); i < text.length(); i++) {

            if (text.charAt(i) == '\\') {
                i++;
            } else if (text.startsWith(delimiter, i)) {
                return i + delimiter.length() - 1;
            }
        }

        throw new N3BadPatchException("Unterminated string literal");
    }
}
