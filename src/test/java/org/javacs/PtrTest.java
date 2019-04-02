package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.net.URI;
import java.util.Collections;
import org.junit.Test;

public class PtrTest {

    static JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();
    static String file = "/org/javacs/example/Ptrs.java";
    static URI uri = FindResource.uri(file);
    static CompileBatch compile = server.compiler.compileBatch(Collections.singleton(uri));

    @Test
    public void classPtr() {
        var el = compile.element(uri, 3, 15).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }

    @Test
    public void fieldPtr() {
        var el = compile.element(uri, 4, 20).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs#field"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }

    @Test
    public void emptyMethodPtr() {
        var el = compile.element(uri, 6, 20).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs#method()"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }

    @Test
    public void intMethodPtr() {
        var el = compile.element(uri, 8, 20).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs#method(int)"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }

    @Test
    public void stringMethodPtr() {
        var el = compile.element(uri, 10, 20).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs#method(java.lang.String)"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }

    @Test
    public void constructorPtr() {
        var el = compile.element(uri, 12, 13).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs#<init>(int)"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }

    @Test
    public void innerClassPtr() {
        var el = compile.element(uri, 14, 20).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs.InnerClass"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }

    @Test
    public void innerFieldPtr() {
        var el = compile.element(uri, 15, 20).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs.InnerClass#innerField"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }

    @Test
    public void innerEmptyMethodPtr() {
        var el = compile.element(uri, 17, 25).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs.InnerClass#innerMethod()"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }

    @Test
    public void innerConstructorPtr() {
        var el = compile.element(uri, 19, 21).get();
        var ptr = new Ptr(el);
        assertThat(ptr.toString(), equalTo("org.javacs.example/Ptrs.InnerClass#<init>()"));

        var copy = new Ptr(ptr.toString());
        assertThat(copy, equalTo(ptr));
    }
}
