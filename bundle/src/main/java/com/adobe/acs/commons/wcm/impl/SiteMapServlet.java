/*
 * #%L
 * ACS AEM Commons Bundle
 * %%
 * Copyright (C) 2014 Adobe
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.adobe.acs.commons.wcm.impl;

import com.day.cq.commons.Externalizer;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.DamConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageFilter;
import com.day.cq.wcm.api.PageManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.AbstractResourceVisitor;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.servlet.ServletException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component(metatype = true, label = "ACS AEM Commons - Site Map Servlet", description = "Site Map Servlet",
        configurationFactory = true)
@Service
@SuppressWarnings("serial")
@Properties({ @Property(name = "sling.servlet.resourceTypes", unbounded = PropertyUnbounded.ARRAY,
        label = "Sling Resource Type", description = "Sling Resource Type for the Home Page component or components."),
        @Property(name = "sling.servlet.selectors", value = "sitemap", propertyPrivate = true),
        @Property(name = "sling.servlet.extensions", value = "xml", propertyPrivate = true),
        @Property(name = "sling.servlet.methods", value = "GET", propertyPrivate = true),
        @Property(
                name = "webconsole.configurationFactory.nameHint",
                value = "Site Map for: {externalizer.domain}, on resource types: [{sling.servlet.resourceTypes}]")
})
public final class SiteMapServlet extends SlingSafeMethodsServlet {
    private static final Logger log = LoggerFactory.getLogger(SiteMapServlet.class);

    private static final FastDateFormat DATE_FORMAT = FastDateFormat.getInstance("yyyy-MM-dd");

    private static final boolean DEFAULT_INCLUDE_LAST_MODIFIED = false;

    private static final String DEFAULT_EXTERNALIZER_DOMAIN = "publish";

    @Property(value = DEFAULT_EXTERNALIZER_DOMAIN, label = "Externalizer Domain",
            description = "Must correspond to a configuration of the Externalizer component.")
    private static final String PROP_EXTERNALIZER_DOMAIN = "externalizer.domain";

    @Property(boolValue = DEFAULT_INCLUDE_LAST_MODIFIED, label = "Include Last Modified",
            description = "If true, the last modified value will be included in the sitemap.")
    private static final String PROP_INCLUDE_LAST_MODIFIED = "include.lastmod";

    @Property(label = "Change Frequency Properties", unbounded = PropertyUnbounded.ARRAY,
            description = "The set of JCR property names which will contain the change frequency value.")
    private static final String PROP_CHANGE_FREQUENCY_PROPERTIES = "changefreq.properties";

    @Property(label = "Priority Properties", unbounded = PropertyUnbounded.ARRAY,
            description = "The set of JCR property names which will contain the priority value.")
    private static final String PROP_PRIORITY_PROPERTIES = "priority.properties";

    @Property(label = "DAM Folder Property",
            description = "The JCR property name which will contain DAM folders to include in the sitemap.")
    private static final String PROP_DAM_ASSETS_PROPERTY = "damassets.property";

    @Property(label = "DAM Asset MIME Types", unbounded = PropertyUnbounded.ARRAY,
            description = "MIME types allowed for DAM assets.")
    private static final String PROP_DAM_ASSETS_TYPES = "damassets.types";

    private static final String NS = "http://www.sitemaps.org/schemas/sitemap/0.9";

    @Reference
    private Externalizer externalizer;

    private String externalizerDomain;

    private boolean includeLastModified;

    private String[] changefreqProperties;

    private String[] priorityProperties;

    private String damAssetProperty;

    private List<String> damAssetTypes;

    @Activate
    protected void activate(Map<String, Object> properties) {
        this.externalizerDomain = PropertiesUtil.toString(properties.get(PROP_EXTERNALIZER_DOMAIN),
                DEFAULT_EXTERNALIZER_DOMAIN);
        this.includeLastModified = PropertiesUtil.toBoolean(properties.get(PROP_INCLUDE_LAST_MODIFIED), DEFAULT_INCLUDE_LAST_MODIFIED);
        this.changefreqProperties = PropertiesUtil.toStringArray(properties.get(PROP_CHANGE_FREQUENCY_PROPERTIES), new String[0]);
        this.priorityProperties = PropertiesUtil.toStringArray(properties.get(PROP_PRIORITY_PROPERTIES), new String[0]);
        this.damAssetProperty = PropertiesUtil.toString(properties.get(PROP_DAM_ASSETS_PROPERTY), "");
        this.damAssetTypes = Arrays.asList(PropertiesUtil.toStringArray(properties.get(PROP_DAM_ASSETS_TYPES), new String[0]));
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType(request.getResponseContentType());
        ResourceResolver resourceResolver = request.getResourceResolver();
        PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
        Page page = pageManager.getContainingPage(request.getResource());

        XMLOutputFactory outputFactory = XMLOutputFactory.newFactory();
        try {
            XMLStreamWriter stream = outputFactory.createXMLStreamWriter(response.getWriter());
            stream.writeStartDocument("1.0");

            stream.writeStartElement("", "urlset", NS);
            stream.writeNamespace("", NS);


            /* Write the CQ Pages to the Sitemap XML */

            // first do the current page
            writePage(page, stream, resourceResolver);

            for (Iterator<Page> children = page.listChildren(new PageFilter(), true); children.hasNext();) {
                writePage(children.next(), stream, resourceResolver);
            }

            /* Write the DAM Assets to the Sitemap XML */
            if (damAssetTypes.size() > 0 && damAssetProperty.length() > 0) {
                writeAssets(page.getProperties().get(damAssetProperty, new String[]{}), stream, resourceResolver);
            }

            stream.writeEndElement();
            stream.writeEndDocument();

        } catch (XMLStreamException e) {
            throw new IOException(e);
        } catch (AssetResourceVisitorException e) {
            if(e.getCause() instanceof  IOException) {
                throw new IOException(e);
            } else {
                throw new ServletException(e);
            }
        }
    }

    private void writeAssets(final String[] damPaths, final XMLStreamWriter stream,
                             final ResourceResolver resourceResolver) throws AssetResourceVisitorException {
        for (final String damPath : damPaths) {
            if (StringUtils.isNotBlank(damPath)) {
                AssetResourceVisitor assetVisitor = new AssetResourceVisitor(stream);
                assetVisitor.accept(resourceResolver.getResource(damPath));
            }
        }
    }
    private void writePage(Page page, XMLStreamWriter stream, ResourceResolver resolver) throws XMLStreamException {
        stream.writeStartElement(NS, "url");


        String loc = externalizer.externalLink(resolver, externalizerDomain,
                String.format("%s.html", page.getPath()));
        writeElement(stream, "loc", loc);

        if (includeLastModified) {
            Calendar cal = page.getLastModified();
            if (cal != null) {
                writeElement(stream, "lastmod", DATE_FORMAT.format(cal));
            }
        }

        final ValueMap properties = page.getProperties();
        writeFirstPropertyValue(stream, "changefreq", changefreqProperties, properties);
        writeFirstPropertyValue(stream, "priority", priorityProperties, properties);

        stream.writeEndElement();
    }

    private void writeAsset(Asset asset, XMLStreamWriter stream, ResourceResolver resolver) throws XMLStreamException {
        stream.writeStartElement(NS, "url");


        String loc = externalizer.externalLink(resolver, externalizerDomain, asset.getPath());
        writeElement(stream, "loc", loc);

        if (includeLastModified) {
            long lastModified = asset.getLastModified();
            if (lastModified > 0) {
                writeElement(stream, "lastmod", DATE_FORMAT.format(lastModified));
            }
        }

        Resource contentResource = asset.adaptTo(Resource.class).getChild("jcr:content");
        if (contentResource != null) {
            final ValueMap properties = contentResource.getValueMap();
            writeFirstPropertyValue(stream, "changefreq", changefreqProperties, properties);
            writeFirstPropertyValue(stream, "priority", priorityProperties, properties);
        }

        stream.writeEndElement();
    }

    private void writeFirstPropertyValue(final XMLStreamWriter stream, final String elementName, final String[] propertyNames,
            final ValueMap properties) throws XMLStreamException {
        for (String prop : propertyNames) {
            String value = properties.get(prop, String.class);
            if (value != null) {
                writeElement(stream, elementName, value);
                break;
            }
        }
    }

    private void writeElement(final XMLStreamWriter stream, final String elementName, final String text) throws XMLStreamException {
        stream.writeStartElement(NS, elementName);
        stream.writeCharacters(text);
        stream.writeEndElement();
    }


    /**
     * Resource tree visitor that looks for dam:Assets, visits them, but not their descendants.
     */
    private final class AssetResourceVisitor extends AbstractResourceVisitor {
        private final XMLStreamWriter stream;

        public AssetResourceVisitor(XMLStreamWriter stream) {
            this.stream = stream;
        }

        private boolean isAsset(Resource resource) throws RepositoryException {
            Node node = resource.adaptTo(Node.class);
            return (node != null && node.isNodeType(DamConstants.NT_DAM_ASSET);
        }

        @Override
        public void accept(final Resource resource) {
            // Don't try to traverse null resources
            if (resource == null) { return; }

            try {
                if (isAsset(resource)) {
                    // perform work on the asset but DONT traverse its children
                    this.visit(resource);
                } else {
                    // This is not an asset so inspect its descendants to see if they are Assets
                    this.traverseChildren(resource.listChildren());
                }
            } catch (RepositoryException e) {
                log.error("Could not check the node type of  [ {} ].", resource.getPath(), e);
                throw new AssetResourceVisitorException(e);
            }
        }

        @Override
        protected void visit(final Resource resource) {
            Asset asset = resource.adaptTo(Asset.class);

            if(asset != null) {
                if (damAssetTypes.contains(asset.getMimeType()))  {
                    try {
                        writeAsset(asset, this.stream, resource.getResourceResolver());
                    } catch (XMLStreamException e) {
                        log.error("Unable to write Asset [ {} ] to XML stream.", resource.getPath(), e);
                        throw new AssetResourceVisitorException(e);
                    }
                }
            } else {
                log.warn("Resource [ {} ] could not be adapted to an Asset.", resource.getPath());
            }
        }
    }

    /**
     * Unchecked Exception class as the AbstractResourceVisitor does not support throwing of checked exceptions.
     */
    private class AssetResourceVisitorException extends RuntimeException {
        public AssetResourceVisitorException(Exception e) {
            super(e);
        }
    }
}
