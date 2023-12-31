package testexecutor.gdd;

import fragment.ACodeFragment;
import fragment.GraphCodeFragment;
import fragment.HDDCodeFragment;
import fragment.ICodeFragment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import testexecutor.TestExecutorOptions;
import testexecutor.hdd.HDDTestExecutor;
import utility.JavaParserUtility;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class GDDTestExecutor extends HDDTestExecutor {
    protected final GraphDB m_graphDB;
    protected final Map<Long, GraphCodeFragment> m_fragments;
    protected Set<Long> m_activeFragments;

    public GDDTestExecutor(TestExecutorOptions options) {
        super(options);
        DateTimeFormatter timeStampPattern = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String nodeIdentifierSuffix = "_" + LocalDateTime.now().format(timeStampPattern);
        m_graphDB = new GraphDB(nodeIdentifierSuffix);
        System.out.println("Manually query the graph in the neo4j browser: http://localhost:7474/browser/");
        System.out.println("Example Query: \"MATCH (f:Fragment" + nodeIdentifierSuffix + ") RETURN *;\"");

        m_fragments = new HashMap<>();
        m_activeFragments = new HashSet<>();
    }

    @Override
    public List<ICodeFragment> extractFragments() {
        super.extractFragments();
        Set<Long> removedFragments = m_graphDB.deleteUnneccessaryFragments();
        m_fragments.entrySet().removeIf(e -> removedFragments.contains(e.getKey()));
        m_graphDB.calculateCrossTreeDependencies();
        //m_graphDB.calculateGuarantees(); TODO check if this is actually beneficial

        // TODO maybe check for cycles in graph
        return Collections.emptyList();
    }

    @Override
    protected HDDCodeFragment transformToFragements(CompilationUnit javaAST, List<JavaParserUtility.Token> tokens, String relativeFileName, AtomicInteger fragmentNr) {
        HDDCodeFragment root = super.transformToFragements(javaAST, tokens, relativeFileName, fragmentNr);
        writeFragmentsToDatabase(Collections.singletonList(root), null);
        return null;
    }

    protected void writeFragmentsToDatabase(List<HDDCodeFragment> fragments, Long parentFragmentNodeId) {
        List<Long> fragmentNodeIds = m_graphDB.addFragmentNodes(fragments);
        if (parentFragmentNodeId != null) {
            m_graphDB.addASTDependencies(fragmentNodeIds, parentFragmentNodeId);
        }
        // add all fragments to map
        for (int i = 0; i < fragmentNodeIds.size(); i++) {
            long id = fragmentNodeIds.get(i);
            HDDCodeFragment fragment = fragments.get(i);
            m_fragments.put(id, new GraphCodeFragment(fragment.getPath(), id, fragment.getTokens()));
        }
        // recursively call method for children
        for (int i = 0; i < fragmentNodeIds.size(); i++) {
            long id = fragmentNodeIds.get(i);
            HDDCodeFragment fragment = fragments.get(i);
            writeFragmentsToDatabase(fragment.getChildren(), id);
        }
    }

    @Override
    protected Map<String, String> mapFragmentsToFiles(List<ICodeFragment> selectedFragments) {
        Set<Long> selectedActiveNodes = selectedFragments.stream()
                .map(GraphCodeFragment.class::cast)
                .map(ACodeFragment::getFragmentNumber)
                .collect(Collectors.toSet());
        Set<Long> excluded = new HashSet<>(m_activeFragments);
        excluded.removeAll(selectedActiveNodes);
        excluded.addAll(m_graphDB.getAllExcludedNodeIds(excluded));

        Map<String, Set<GraphCodeFragment>> fragmentsByFile = m_fragments.entrySet()
                .stream()
                .filter(e -> !excluded.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .map(GraphCodeFragment.class::cast)
                .collect(Collectors.groupingBy(ICodeFragment::getPath, Collectors.mapping(fr -> fr, Collectors.toSet())));

        Map<String, String> files = new HashMap<>();
        for (Map.Entry<String, Set<GraphCodeFragment>> entry : fragmentsByFile.entrySet()) {
            String fileName = entry.getKey();
            StringBuilder sb = new StringBuilder();
            entry.getValue().stream()
                    .flatMap(fr -> fr.getTokens().stream())
                    .sorted(Comparator.comparing(t -> t.start))
                    .forEach(token -> sb.append(token.code));

            files.put(fileName, sb.toString());
        }

        return files;
    }

    // returns the fragments that the ddmin algorithm should be run on at this moment
    public List<ICodeFragment> getActiveFragments() {
        m_activeFragments = m_graphDB.calculateActiveFragments(getOptions().getGraphAlgorithmFragmentLimit());
        return m_activeFragments.stream()
                .map(m_fragments::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public void addFixedFragments(List<ICodeFragment> fragments) {
        Set<Long> fixedNodes = fragments.stream()
                .map(ICodeFragment::getFragmentNumber)
                .collect(Collectors.toSet());
        m_graphDB.markFragmentNodesAsFixed(fixedNodes);
    }

    public void addDiscardedFragments(List<ICodeFragment> fragments) {
        Set<Long> nodesToDiscard = fragments.stream()
                .map(f -> (GraphCodeFragment) f)
                .map(ACodeFragment::getFragmentNumber)
                .collect(Collectors.toSet());
        Set<Long> allDiscardedNodes = m_graphDB.discardFragmentNodes(nodesToDiscard);
        allDiscardedNodes.forEach(m_fragments::remove);
        System.out.println("Discarded " + fragments.size() + " active fragments. " + allDiscardedNodes.size() + " fragments, including dependent nodes");
    }

    public int getNumberOfRemainingFragments() {
        return m_fragments.size();
    }

    @Override
    public void changeSourceToOutputFolder() {
        super.changeSourceToOutputFolder();

        // mark all nodes as free for another run of the algorithm
        m_graphDB.freeAllFragmentNodes();
        m_activeFragments.clear();
    }
}
