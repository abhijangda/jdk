/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * The implementation of the <em>javadoc</em> tool and associated doclets.
 *
 * <p>Internally, <em>javadoc</em> is composed of two primary parts:
 * the {@link jdk.javadoc.internal.tool tool}, and a series of
 * {@link jdk.javadoc.internal.doclets doclets}.
 *
 * <p>The tool provides a common infrastructure for command-line processing,
 * and for reading the declarations and documentation comments in Java source files,
 * while doclets provide a user-selectable backend for determining
 * how to process the declarations and their documentation comments.
 *
 * <p>The following provides a top-down description of the overall <em>javadoc</em>
 * software stack.
 *
 * <dl>
 *   <dt>Doclets
 *   <dd>
 *      <dl>
 *        <dt id="std-doclet">The Standard Doclet
 *        <dd><p>
 *          The {@link jdk.javadoc.doclet.StandardDoclet} is a thin public wrapper
 *          around the internal HTML doclet.
 *
 *        <dt id="html-doclet">The HTML Doclet
 *        <dd><p>
 *          The {@link jdk.javadoc.internal.doclets.formats.html.HtmlDoclet} class
 *          and other classes in the
 *          {@link jdk.javadoc.internal.doclets.formats.html formats.html} package
 *          customize the abstract pages generated by the toolkit layer to generate
 *          HTML pages.  Some pages are specific to the HTML output format,
 *          and do not have an abstract builder in the toolkit layer.
 *
 *          <p>Individual pages of output are generated by page-specific subtypes of
 *          {@link jdk.javadoc.internal.doclets.formats.html.HtmlDocletWriter}.
 *
 *          <p>The {@link jdk.javadoc.internal.doclets.formats.html.HtmlConfiguration} class
 *          provides configuration information that is relevant to all the generated pages.
 *          The class extends the {@link jdk.javadoc.internal.doclets.toolkit.BaseConfiguration}
 *          class provided by the toolkit layer.
 *
 *          <p>The classes in the {@code formats.html} package use an internal
 *          library in the
 *          {@link jdk.javadoc.internal.doclets.formats.html.markup formats.html.markup} package,
 *          to create trees (or acyclic graphs) of
 *          {@linkplain jdk.javadoc.internal.doclets.formats.html.markup.HtmlTree HTML tree nodes}.
 *          Apart from using a common format-neutral supertype,
 *          {@link jdk.javadoc.internal.doclets.toolkit.Content}, the {@code markup} library
 *          is mostly independent of the rest of the javadoc software stack.
 *
 *        <dt id="toolkit">Toolkit
 *        <dd><p>
 *          The {@link jdk.javadoc.internal.doclets.toolkit toolkit} package provides
 *          support for a format-neutral
 *          {@linkplain jdk.javadoc.internal.doclets.toolkit.AbstractDoclet abstract doclet},
 *          which uses
 *          {@linkplain jdk.javadoc.internal.doclets.toolkit.builders.AbstractBuilder builders}
 *          to generate pages of abstract
 *          {@linkplain jdk.javadoc.internal.doclets.toolkit.Content content}.
 *
 *          <p>The format-specific content for each page is provided by implementations
 *          of various <em>writer</em> interfaces, created by a format-specific
 *          {@linkplain jdk.javadoc.internal.doclets.toolkit.WriterFactory writer factory}.
 *
 *          <p>The {@link jdk.javadoc.internal.doclets.toolkit.BaseConfiguration} provides
 *          configuration information that is relevant to all the generated pages.
 *          Some of the information is provided by abstract methods which are implemented
 *          in format-specific subtypes of the class.
 *
 *          <p>The toolkit layer also provides
 *          {@linkplain jdk.javadoc.internal.doclets.toolkit.util utility classes}
 *          used by this layer and by format-specific layers.
 *
 *          <p id="workarounds">Generally, it is intended that doclets should use the
 *          {@link javax.lang.model Language Model API} to navigate the structure of a Java program,
 *          without needing to access any internal details of the underlying <em>javac</em> implementation.
 *          However, there are still some shortcomings of the Language Model API,
 *          and so it is still necessary to provide limited access to some of those internal details.
 *          Although not enforceable by the module system, by design the access to <em>javac</em>
 *          internal details by doclets based on {@code AbstractDoclet} is restricted to the aptly-named
 *          {@link jdk.javadoc.internal.doclets.toolkit.WorkArounds} class.
 *
 *        <dt>Other Doclets
 *        <dd><p>
 *          Doclets are obviously not required to use
 *          {@link jdk.javadoc.internal.doclets.toolkit.AbstractDoclet} and other classes in
 *          the toolkit layer. In times past, it was common to write doclets to analyze
 *          code using the then-current API as an early version of a Java language model.
 *          That old API has been replaced by the {@link javax.lang.model Language Model API},
 *          and tools that wish to use that API to analyze Java programs have a choice of
 *          how to invoke it, using the <em>javac</em> support for
 *          {@linkplain javax.annotation.processing annotation processing},
 *          or {@linkplain com.sun.source.util.Plugin plugins}, as well as doclets.
 *          Which is best for any application will depend of the circumstances, but
 *          if a tool does not need access to the documentation comments in a program,
 *          it is possible that one of the other invocation mechanisms will be more convenient.
 *
 *      </dl>
 *
 *   <dt>The Doclet Interface
 *   <dd><p>
 *     The {@linkplain jdk.javadoc.doclet Doclet API} is the interface layer between
 *     the <em>javadoc</em> tool and the code to process the elements specified to the tool.
 *
 *     <p>Above this layer, in any doclet, the code is expected to use the
 *     {@linkplain javax.lang.model Language Model API} to navigate around the specified
 *     elements, and/or the {@linkplain com.sun.source.doctree DocTree API} to examine
 *     the corresponding documentation comments.
 *
 *   <dt>The <em>javadoc</em> Tool
 *   <dd><p>
 *       After reading the command-line options, the tool uses a modified <em>javac</em>
 *       front end to read the necessary files and thus instantiate the
 *       {@linkplain javax.lang.model.element.Element elements} to be made available to
 *       the doclet that will be used to process them.
 *
 *       The tool uses an internal feature of the <em>javac</em> architecture, which
 *       allows various components to be replaced by subtypes with modified behavior.
 *       This is done by pre-registering the desired components in the <em>javac</em>
 *       {@code Context}.
 *       The tool uses this mechanism to do the following:
 *       <ul>
 *         <li>although source files are parsed in their entirety, the
 *           content of method bodies is quickly discarded as unnecessary;
 *         <li>the class reader is updated to handle {@code package.html}
 *           files in any package directories that are read; and
 *         <li>the compilation pipeline for each source file is terminated
 *           after the <em>parse</em> and <em>enter</em> phases, meaning that
 *           the files are processed enough to instantiate the elements to
 *           be made available to the doclet, but no more.
 *       </ul>
 * </dl>
 *
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 *
 * @see <a href="https://openjdk.java.net/groups/compiler/javadoc-architecture.html">JavaDoc Architecture</a>
 * @see <a href="https://openjdk.java.net/groups/compiler/using-new-doclet.html">Using the new Doclet API</a>
 * @see <a href="https://openjdk.java.net/groups/compiler/processing-code.html">Processing Code</a>
 */
package jdk.javadoc.internal;