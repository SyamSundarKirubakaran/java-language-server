package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.lsp.*;

public class CompileFile implements AutoCloseable {
    private final JavaCompilerService parent;
    public final URI file;
    public final String contents;
    private final TaskPool.Borrow borrow;
    private final Trees trees;
    public final CompilationUnitTree root;

    CompileFile(JavaCompilerService parent, URI file) {
        this.parent = parent;
        this.file = file;
        this.contents = FileStore.contents(file);
        this.borrow = CompileFocus.singleFileTask(parent, file, contents);
        this.trees = Trees.instance(borrow.task);
        var profiler = new Profiler();
        borrow.task.addTaskListener(profiler);
        try {
            this.root = borrow.task.parse().iterator().next();
            // The results of borrow.task.analyze() are unreliable when errors are present
            // You can get at `Element` values using `Trees`
            borrow.task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        profiler.print();
    }

    @Override
    public void close() {
        borrow.close();
    }

    public SourcePositions sourcePositions() {
        return trees.getSourcePositions();
    }

    /**
     * Find all elements in `file` that get turned into code-lenses. This needs to match the result of
     * `ParseFile#declarations`
     */
    public List<Element> declarations() {
        var paths = ParseFile.declarations(root);
        var els = new ArrayList<Element>();
        for (var p : paths) {
            var e = trees.getElement(p);
            assert e != null;
            els.add(e);
        }
        return els;
    }

    public Index index(List<Element> declarations) {
        return new Index(borrow.task, root, parent.diags, declarations);
    }

    public Optional<Element> element(int line, int character) {
        // LOG.info(String.format("Looking for element at %s(%d,%d)...", file.getPath(), line, character));

        // First, look for a tree path
        var path = CompileFocus.findPath(borrow.task, root, line, character);
        if (path == null) {
            // LOG.info("...found nothing");
            return Optional.empty();
        }
        // LOG.info(String.format("...found tree `%s`", Parser.describeTree(path.getLeaf())));

        // Then, convert the path to an element
        var el = trees.getElement(path);
        if (el == null) {
            // LOG.info(String.format("...tree does not correspond to an element"));
            return Optional.empty();
        }

        return Optional.of(el);
    }

    public Optional<TreePath> path(Element e) {
        return Optional.ofNullable(trees.getPath(e));
    }

    public Optional<TreePath> find(Ptr target) {
        class FindPtr extends TreePathScanner<Void, Void> {
            TreePath found = null;

            boolean toStringEquals(Object left, Object right) {
                return Objects.equals(Objects.toString(left, ""), Objects.toString(right, ""));
            }

            /** Check if the declaration at the current path is the same symbol as `e` */
            boolean sameSymbol() {
                var path = getCurrentPath();
                var el = trees.getElement(path);
                return new Ptr(el).equals(target);
            }

            void check() {
                if (sameSymbol()) {
                    found = getCurrentPath();
                }
            }

            @Override
            public Void visitClass(ClassTree node, Void aVoid) {
                check();
                return super.visitClass(node, aVoid);
            }

            @Override
            public Void visitMethod(MethodTree node, Void aVoid) {
                check();
                // Ptr can't point inside a method
                return null;
            }

            @Override
            public Void visitVariable(VariableTree node, Void aVoid) {
                check();
                // Ptr can't point inside a method
                return null;
            }
        }
        var find = new FindPtr();
        find.scan(root, null);
        return Optional.ofNullable(find.found);
    }

    public Optional<Range> range(TreePath path) {
        return ParseFile.range(borrow.task, contents, path);
    }

    private List<Element> overrides(ExecutableElement method) {
        var elements = borrow.task.getElements();
        var types = borrow.task.getTypes();
        var results = new ArrayList<Element>();
        var enclosingClass = (TypeElement) method.getEnclosingElement();
        var enclosingType = enclosingClass.asType();
        for (var superClass : types.directSupertypes(enclosingType)) {
            var e = (TypeElement) types.asElement(superClass);
            for (var other : e.getEnclosedElements()) {
                if (!(other instanceof ExecutableElement)) continue;
                if (elements.overrides(method, (ExecutableElement) other, enclosingClass)) {
                    results.add(other);
                }
            }
        }
        return results;
    }

    private boolean hasOverrideAnnotation(ExecutableElement method) {
        for (var ann : method.getAnnotationMirrors()) {
            var type = ann.getAnnotationType();
            var el = type.asElement();
            var name = el.toString();
            if (name.equals("java.lang.Override")) {
                return true;
            }
        }
        return false;
    }

    /** Find methods that override a method from a superclass but don't have an @Override annotation. */
    public List<TreePath> needsOverrideAnnotation() {
        LOG.info(String.format("Looking for methods that need an @Override annotation in %s ...", file.getPath()));

        var results = new ArrayList<TreePath>();
        class FindMissingOverride extends TreePathScanner<Void, Void> {
            @Override
            public Void visitMethod(MethodTree t, Void __) {
                var method = (ExecutableElement) trees.getElement(getCurrentPath());
                var supers = overrides(method);
                if (!supers.isEmpty() && !hasOverrideAnnotation(method)) {
                    var overridesMethod = supers.get(0);
                    var overridesClass = overridesMethod.getEnclosingElement();
                    LOG.info(
                            String.format(
                                    "...`%s` has no @Override annotation but overrides `%s.%s`",
                                    method, overridesClass, overridesMethod));
                    results.add(getCurrentPath());
                }
                return super.visitMethod(t, null);
            }
        }
        new FindMissingOverride().scan(root, null);
        return results;
    }

    /**
     * Figure out what imports this file should have. Star-imports like `import java.util.*` are converted to individual
     * class imports. Missing imports are inferred by looking at imports in other source files.
     */
    public List<String> fixImports() {
        // Check diagnostics for missing imports
        var unresolved = new HashSet<String>();
        for (var d : parent.diags) {
            if (d.getCode().equals("compiler.err.cant.resolve.location") && d.getSource().toUri().equals(file)) {
                long start = d.getStartPosition(), end = d.getEndPosition();
                var id = contents.substring((int) start, (int) end);
                if (id.matches("[A-Z]\\w+")) {
                    unresolved.add(id);
                } else LOG.warning(id + " doesn't look like a class");
            } else if (d.getMessage(null).contains("cannot find to")) {
                var lines = d.getMessage(null).split("\n");
                var firstLine = lines.length > 0 ? lines[0] : "";
                LOG.warning(String.format("%s %s doesn't look like to-not-found", d.getCode(), firstLine));
            }
        }
        // Look at imports in other classes to help us guess how to fix imports
        // TODO cache parsed imports on a per-file basis
        var sourcePathImports = Parser.existingImports(FileStore.all());
        var classes = new HashSet<String>();
        classes.addAll(parent.jdkClasses);
        classes.addAll(parent.classPathClasses);
        var fixes = Parser.resolveSymbols(unresolved, sourcePathImports, classes);
        // Figure out which existing imports are actually used
        var trees = Trees.instance(borrow.task);
        var references = new HashSet<String>();
        class FindUsedImports extends TreePathScanner<Void, Void> {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                var e = trees.getElement(getCurrentPath());
                if (e instanceof TypeElement) {
                    var t = (TypeElement) e;
                    var qualifiedName = t.getQualifiedName().toString();
                    var lastDot = qualifiedName.lastIndexOf('.');
                    var packageName = lastDot == -1 ? "" : qualifiedName.substring(0, lastDot);
                    var thisPackage = Objects.toString(root.getPackageName(), "");
                    // java.lang.* and current package are imported by default
                    if (!packageName.equals("java.lang")
                            && !packageName.equals(thisPackage)
                            && !packageName.equals("")) {
                        references.add(qualifiedName);
                    }
                }
                return null;
            }
        }
        new FindUsedImports().scan(root, null);
        // Take the intersection of existing imports ^ existing identifiers
        var qualifiedNames = new HashSet<String>();
        for (var i : root.getImports()) {
            var imported = i.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) {
                var packageName = Parser.mostName(imported);
                var isUsed = references.stream().anyMatch(r -> r.startsWith(packageName));
                if (isUsed) qualifiedNames.add(imported);
                else LOG.warning("There are no references to package " + imported);
            } else {
                if (references.contains(imported)) qualifiedNames.add(imported);
                else LOG.warning("There are no references to class " + imported);
            }
        }
        // Add qualified names from fixes
        qualifiedNames.addAll(fixes.values());
        // Sort in alphabetical order
        var sorted = new ArrayList<String>();
        sorted.addAll(qualifiedNames);
        Collections.sort(sorted);
        return sorted;
    }

    public List<String> allClassNames() {
        var result = new ArrayList<String>();
        class FindClasses extends TreeScanner<Void, Void> {
            @Override
            public Void visitClass(ClassTree classTree, Void __) {
                var className = Objects.toString(classTree.getSimpleName(), "");
                result.add(className);
                return null;
            }
        }
        root.accept(new FindClasses(), null);
        return result;
    }

    public Predicate<List<Ptr>> signatureMatches() {
        // Precompute qualified names of all classes in this file
        var thisClasses = new ArrayList<String>();
        for (var c : root.getTypeDecls()) {
            var path = trees.getPath(root, c);
            var el = (TypeElement) trees.getElement(path);
            var name = el.getQualifiedName().toString();
            thisClasses.add(name);
        }
        return i -> {
            // For each pointer, check if it refers to something in this file that no longer exists
            for (var ptr : i) {
                if (thisClasses.contains(ptr.qualifiedClassName()) && !find(ptr).isPresent()) {
                    LOG.info(
                            String.format("`%s` refers to signature that no longer exists in %s", ptr, file.getPath()));
                    return false;
                }
            }
            return true;
        };
    }

    private static final Logger LOG = Logger.getLogger("main");
}
