/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.jcr.resource.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import javax.jcr.Session;

import org.apache.commons.collections.BidiMap;
import org.apache.commons.collections.bidimap.TreeBidiMap;
import org.apache.sling.api.resource.ResourceProvider;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.osgi.OsgiUtil;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.resource.JcrResourceResolverFactory;
import org.apache.sling.jcr.resource.JcrResourceTypeProvider;
import org.apache.sling.jcr.resource.PathResourceTypeProvider;
import org.apache.sling.jcr.resource.internal.helper.MapEntries;
import org.apache.sling.jcr.resource.internal.helper.Mapping;
import org.apache.sling.jcr.resource.internal.helper.ResourceProviderEntry;
import org.apache.sling.jcr.resource.internal.helper.ResourceProviderEntryException;
import org.apache.sling.jcr.resource.internal.helper.jcr.JcrResourceProviderEntry;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>JcrResourceResolverFactoryImpl</code> is the
 * {@link JcrResourceResolverFactory} service providing the following functionality:
 * <ul>
 * <li><code>JcrResourceResolverFactory</code> service
 * <li>Bundle listener to load initial content and manage OCM mapping descriptors provided
 * by bundles.
 * <li>Fires OSGi EventAdmin events on behalf of internal helper objects
 * </ul>
 * 
 * @scr.component immediate="true" label="%resource.resolver.name"
 *                description="%resource.resolver.description"
 * @scr.property name="service.description"
 *               value="Sling JcrResourceResolverFactory Implementation"
 * @scr.property name="service.vendor" value="The Apache Software Foundation"
 * @scr.service interface="org.apache.sling.jcr.resource.JcrResourceResolverFactory"
 * @scr.reference name="ResourceProvider"
 *                interface="org.apache.sling.api.resource.ResourceProvider"
 *                cardinality="0..n" policy="dynamic"
 * @scr.reference name="JcrResourceTypeProvider"
 *                interface="org.apache.sling.jcr.resource.JcrResourceTypeProvider"
 *                cardinality="0..n" policy="dynamic"
 * @scr.reference name="PathResourceTypeProvider"
 *                interface="org.apache.sling.jcr.resource.PathResourceTypeProvider"
 *                cardinality="0..n" policy="dynamic"
 * @scr.reference name="Repository" interface="org.apache.sling.jcr.api.SlingRepository"
 * 
 * 
 *                NOTE: Although th SCR Statements are here, we manually maintain the
 *                mappings so that we can extend. The Manifest comes from the Sling Jar which
 *                we extend, and so we cant run the scr plugin as that would miss some statements.
 *                
 * 
 */
public class JcrResourceResolverFactoryImpl implements JcrResourceResolverFactory {
  public static final String SAKAI_EXTENSION_BUNDLE = "sakai.extension";

  public final static class ResourcePattern {
    public final Pattern pattern;

    public final String replacement;

    public ResourcePattern(final Pattern p, final String r) {
      this.pattern = p;
      this.replacement = r;
    }
  }

  /**
   * @scr.property values.1="/apps" values.2="/libs"
   */
  public static final String PROP_PATH = "resource.resolver.searchpath";

  /**
   * Defines whether namespace prefixes of resource names inside the path (e.g.
   * <code>jcr:</code> in <code>/home/path/jcr:content</code>) are mangled or not.
   * <p>
   * Mangling means that any namespace prefix contained in the path is replaced as per the
   * generic substitution pattern <code>/([^:]+):/_$1_/</code> when calling the
   * <code>map</code> method of the resource resolver. Likewise the <code>resolve</code>
   * methods will unmangle such namespace prefixes according to the substituation pattern
   * <code>/_([^_]+)_/$1:/</code>.
   * <p>
   * This feature is provided since there may be systems out there in the wild which
   * cannot cope with URLs containing colons, even though they are perfectly valid
   * characters in the path part of URI references with a scheme.
   * <p>
   * The default value of this property if no configuration is provided is
   * <code>true</code>.
   * 
   * @scr.property value="true" type="Boolean"
   */
  private static final String PROP_MANGLE_NAMESPACES = "resource.resolver.manglenamespaces";

  /**
   * @scr.property value="true" type="Boolean"
   */
  private static final String PROP_ALLOW_DIRECT = "resource.resolver.allowDirect";

  /**
   * The resolver.virtual property has no default configuration. But the sling maven
   * plugin and the sling management console cannot handle empty multivalue properties at
   * the moment. So we just add a dummy direct mapping.
   * 
   * @scr.property values.1="/-/"
   */
  private static final String PROP_VIRTUAL = "resource.resolver.virtual";

  /**
   * @scr.property values.1="/-/" values.2="/content/-/"
   *               Cvalues.3="/apps/&times;/docroot/-/"
   *               Cvalues.4="/libs/&times;/docroot/-/" values.5="/system/docroot/-/"
   */
  private static final String PROP_MAPPING = "resource.resolver.mapping";

  /** default log */
  private final Logger log = LoggerFactory.getLogger(getClass());

  /**
   * The JCR Repository we access to resolve resources
   * 
   */
  private SlingRepository repository;

  /**
   * The (optional) resource type providers.
   */
  protected final List<JcrResourceTypeProviderEntry> jcrResourceTypeProviders = new ArrayList<JcrResourceTypeProviderEntry>();

  private JcrResourceTypeProvider[] jcrResourceTypeProvidersArray;

  /**
   * The (optional) resource type providers.
   */
  protected final List<PathResourceTypeProviderEntry> pathResourceTypeProviders = new ArrayList<PathResourceTypeProviderEntry>();

  private PathResourceTypeProvider[] pathResourceTypeProvidersArray;

  /**
   * List of ResourceProvider services bound before activation of the component.
   */
  private final List<ServiceReference> delayedResourceProviders = new LinkedList<ServiceReference>();

  /**
   * List of JcrResourceTypeProvider services bound before activation of the component.
   */
  protected List<ServiceReference> delayedJcrResourceTypeProviders = new LinkedList<ServiceReference>();

  /**
   * List of PathResourceTypeProvider services bound before activation of the component.
   */
  protected List<ServiceReference> delayedPathResourceTypeProviders = new LinkedList<ServiceReference>();

  protected ComponentContext componentContext;

  // helper for the new JcrResourceResolver2
  private MapEntries mapEntries = MapEntries.EMPTY;

  /** all mappings */
  private Mapping[] mappings;

  /** The fake urls */
  private BidiMap virtualURLMap;

  /** <code>true</code>, if direct mappings from URI to handle are allowed */
  private boolean allowDirect = false;

  // the search path for ResourceResolver.getResource(String)
  private String[] searchPath;

  private ResourceProviderEntry rootProviderEntry;

  // whether to mangle paths with namespaces or not
  private boolean mangleNamespacePrefixes;

  public JcrResourceResolverFactoryImpl() {
    this.rootProviderEntry = new ResourceProviderEntry("/", null, null);
  }

  // ---------- JcrResourceResolverFactory -----------------------------------

  /**
   * Returns a new <code>ResourceResolve</code> for the given session. Note that each call
   * to this method returns a new resource manager instance.
   */
  public ResourceResolver getResourceResolver(Session session) {
    JcrResourceProviderEntry sessionRoot = new JcrResourceProviderEntry(session,
        rootProviderEntry, getJcrResourceTypeProviders());

    return new JcrResourceResolver2(sessionRoot, this, mapEntries);
  }

  protected JcrResourceTypeProvider[] getJcrResourceTypeProviders() {
    return jcrResourceTypeProvidersArray;
  }

  /**
   * @return
   */
  protected PathResourceTypeProvider[] getPathResourceTypeProviders() {
    return pathResourceTypeProvidersArray;
  }

  // ---------- Implementation helpers --------------------------------------

  /** If uri is a virtual URI returns the real URI, otherwise returns null */
  String virtualToRealUri(String virtualUri) {
    return (virtualURLMap != null) ? (String) virtualURLMap.get(virtualUri) : null;
  }

  /**
   * If uri is a real URI for any virtual URI, the virtual URI is returned, otherwise
   * returns null
   */
  String realToVirtualUri(String realUri) {
    return (virtualURLMap != null) ? (String) virtualURLMap.getKey(realUri) : null;
  }

  public BidiMap getVirtualURLMap() {
    return virtualURLMap;
  }

  public Mapping[] getMappings() {
    return mappings;
  }

  String[] getSearchPath() {
    return searchPath;
  }

  boolean isMangleNamespacePrefixes() {
    return mangleNamespacePrefixes;

  }

  MapEntries getMapEntries() {
    return mapEntries;
  }

  /**
   * Getter for rootProviderEntry, making it easier to extend
   * JcrResourceResolverFactoryImpl. See <a
   * href="https://issues.apache.org/jira/browse/SLING-730">SLING-730</a>
   * 
   * @return Our rootProviderEntry
   */
  protected ResourceProviderEntry getRootProviderEntry() {
    return rootProviderEntry;
  }

  // ---------- SCR Integration ---------------------------------------------

  /** Activates this component, called by SCR before registering as a service */
  protected void activate(ComponentContext componentContext) {
    this.componentContext = componentContext;

    Dictionary<?, ?> properties = componentContext.getProperties();

    BidiMap virtuals = new TreeBidiMap();
    String[] virtualList = (String[]) properties.get(PROP_VIRTUAL);
    for (int i = 0; virtualList != null && i < virtualList.length; i++) {
      String[] parts = Mapping.split(virtualList[i]);
      virtuals.put(parts[0], parts[2]);
    }
    virtualURLMap = virtuals;

    List<Mapping> maps = new ArrayList<Mapping>();
    String[] mappingList = (String[]) properties.get(PROP_MAPPING);
    for (int i = 0; mappingList != null && i < mappingList.length; i++) {
      maps.add(new Mapping(mappingList[i]));
    }
    Mapping[] tmp = maps.toArray(new Mapping[maps.size()]);

    // check whether direct mappings are allowed
    Boolean directProp = (Boolean) properties.get(PROP_ALLOW_DIRECT);
    allowDirect = (directProp != null) ? directProp.booleanValue() : true;
    if (allowDirect) {
      Mapping[] tmp2 = new Mapping[tmp.length + 1];
      tmp2[0] = Mapping.DIRECT;
      System.arraycopy(tmp, 0, tmp2, 1, tmp.length);
      mappings = tmp2;
    } else {
      mappings = tmp;
    }

    // from configuration if available
    searchPath = OsgiUtil.toStringArray(properties.get(PROP_PATH));
    if (searchPath != null && searchPath.length > 0) {
      for (int i = 0; i < searchPath.length; i++) {
        // ensure leading slash
        if (!searchPath[i].startsWith("/")) {
          searchPath[i] = "/" + searchPath[i];
        }
        // ensure trailing slash
        if (!searchPath[i].endsWith("/")) {
          searchPath[i] += "/";
        }
      }
    }
    if (searchPath == null) {
      searchPath = new String[] {"/"};
    }

    // namespace mangling
    mangleNamespacePrefixes = OsgiUtil.toBoolean(properties.get(PROP_MANGLE_NAMESPACES),
        false);

    // bind resource providers not bound yet
    for (ServiceReference reference : delayedResourceProviders) {
      bindResourceProvider(reference);
    }
    delayedResourceProviders.clear();
    this.processDelayedJcrResourceTypeProviders();
    this.processDelayedPathResourceTypeProviders();

    // set up the map entries from configuration
    try {
      mapEntries = new MapEntries(this, getRepository());
      plugin = new JcrResourceResolverWebConsolePlugin(componentContext
          .getBundleContext(), this);
    } catch (Exception e) {
      log.error("activate: Cannot access repository, failed setting up Mapping Support",
          e);
    }
  }

  private JcrResourceResolverWebConsolePlugin plugin;

  /** Deativates this component, called by SCR to take out of service */
  protected void deactivate(ComponentContext componentContext) {
    if (plugin != null) {
      plugin.dispose();
      plugin = null;
    }

    if (mapEntries != null) {
      mapEntries.dispose();
      mapEntries = MapEntries.EMPTY;
    }

    this.componentContext = null;
  }

  private ResourcePattern[] getResourcePatterns(String[] patternList) {
    // regexps
    List<ResourcePattern> patterns = new ArrayList<ResourcePattern>();
    if (patternList != null) {
      for (final String p : patternList) {
        int pos = p.lastIndexOf('|');
        if (pos == -1) {
          log.error("Invalid regexp: {}", p);
        } else {
          final String replString = p.substring(pos + 1);
          final Pattern pat = Pattern.compile(p.substring(0, pos));
          patterns.add(new ResourcePattern(pat, replString));
        }
      }
    }
    return patterns.toArray(new ResourcePattern[patterns.size()]);
  }

  protected void processDelayedJcrResourceTypeProviders() {
    synchronized (this.jcrResourceTypeProviders) {
      for (ServiceReference reference : delayedJcrResourceTypeProviders) {
        this.addJcrResourceTypeProvider(reference);
      }
      delayedJcrResourceTypeProviders.clear();
      updateJcrResourceTypeProviderArray();
    }
  }

  protected void addJcrResourceTypeProvider(final ServiceReference reference) {
    final Long id = (Long) reference.getProperty(Constants.SERVICE_ID);
    long ranking = -1;
    if (reference.getProperty(Constants.SERVICE_RANKING) != null) {
      ranking = (Long) reference.getProperty(Constants.SERVICE_RANKING);
    }
    this.jcrResourceTypeProviders.add(new JcrResourceTypeProviderEntry(id, ranking,
        (JcrResourceTypeProvider) this.componentContext.locateService(
            "JcrResourceTypeProvider", reference)));
    Collections.sort(this.jcrResourceTypeProviders,
        new Comparator<JcrResourceTypeProviderEntry>() {

          public int compare(JcrResourceTypeProviderEntry o1,
              JcrResourceTypeProviderEntry o2) {
            if (o1.ranking < o2.ranking) {
              return 1;
            } else if (o1.ranking > o2.ranking) {
              return -1;
            } else {
              if (o1.serviceId < o2.serviceId) {
                return -1;
              } else if (o1.serviceId > o2.serviceId) {
                return 1;
              }
            }
            return 0;
          }
        });

  }

  protected void bindResourceProvider(ServiceReference reference) {

    String serviceName = getServiceName(reference);

    if (componentContext == null) {

      log.debug("bindResourceProvider: Delaying {}", serviceName);

      // delay binding resource providers if called before activation
      delayedResourceProviders.add(reference);

    } else {

      log.debug("bindResourceProvider: Binding {}", serviceName);

      String[] roots = OsgiUtil.toStringArray(reference
          .getProperty(ResourceProvider.ROOTS));
      if (roots != null && roots.length > 0) {

        ResourceProvider provider = (ResourceProvider) componentContext.locateService(
            "ResourceProvider", reference);

        // synchronized insertion of new resource providers into
        // the tree to not inadvertandly loose an entry
        synchronized (this) {

          for (String root : roots) {
            // cut off trailing slash
            if (root.endsWith("/") && root.length() > 1) {
              root = root.substring(0, root.length() - 1);
            }

            try {
              rootProviderEntry.addResourceProvider(root, provider);

              log.debug("bindResourceProvider: {}={} ({})", new Object[] {root, provider,
                  serviceName});
            } catch (ResourceProviderEntryException rpee) {
              log
                  .error(
                      "bindResourceProvider: Cannot register ResourceProvider {} for {}: ResourceProvider {} is already registered",
                      new Object[] {provider, root,
                          rpee.getExisting().getResourceProvider()});
            }
          }
        }
      }

      log.debug("bindResourceProvider: Bound {}", serviceName);
    }
  }

  protected void unbindResourceProvider(ServiceReference reference) {

    String serviceName = getServiceName(reference);

    log.debug("unbindResourceProvider: Unbinding {}", serviceName);

    String[] roots = OsgiUtil
        .toStringArray(reference.getProperty(ResourceProvider.ROOTS));
    if (roots != null && roots.length > 0) {

      // synchronized insertion of new resource providers into
      // the tree to not inadvertandly loose an entry
      synchronized (this) {

        for (String root : roots) {
          // cut off trailing slash
          if (root.endsWith("/") && root.length() > 1) {
            root = root.substring(0, root.length() - 1);
          }

          // TODO: Do not remove this path, if another resource
          // owns it. This may be the case if adding the provider
          // yielded an ResourceProviderEntryException
          rootProviderEntry.removeResourceProvider(root);

          log.debug("unbindResourceProvider: root={} ({})", root, serviceName);
        }
      }
    }

    log.debug("unbindResourceProvider: Unbound {}", serviceName);
  }

  protected void bindJcrResourceTypeProvider(ServiceReference reference) {
    synchronized (this.jcrResourceTypeProviders) {
      if (componentContext == null) {
        delayedJcrResourceTypeProviders.add(reference);
      } else {
        this.addJcrResourceTypeProvider(reference);
        updateJcrResourceTypeProviderArray();
      }
    }
  }

  protected void unbindJcrResourceTypeProvider(ServiceReference reference) {
    synchronized (this.jcrResourceTypeProviders) {
      delayedJcrResourceTypeProviders.remove(reference);
      final long id = (Long) reference.getProperty(Constants.SERVICE_ID);
      final Iterator<JcrResourceTypeProviderEntry> i = this.jcrResourceTypeProviders
          .iterator();
      while (i.hasNext()) {
        final JcrResourceTypeProviderEntry current = i.next();
        if (current.serviceId == id) {
          i.remove();
        }
      }
      updateJcrResourceTypeProviderArray();
    }
  }

  private void updateJcrResourceTypeProviderArray() {
    JcrResourceTypeProvider[] providers = null;
    synchronized (this.jcrResourceTypeProviders) {
      if (this.jcrResourceTypeProviders.size() > 0) {
        providers = new JcrResourceTypeProvider[this.jcrResourceTypeProviders.size()];
        int index = 0;
        final Iterator<JcrResourceTypeProviderEntry> i = this.jcrResourceTypeProviders
            .iterator();
        while (i.hasNext()) {
          providers[index++] = i.next().provider;
          log.info("Added {} at {} ",providers[index-1],index-1);
        }
      }
    }
    log.info("=======================Loaded JCR Resource Type Providers: {} ",Arrays.toString(providers));
    jcrResourceTypeProvidersArray = providers;
  }

  // ------------------------------------------- resource type providers
  // ----------------------------------

  protected void processDelayedPathResourceTypeProviders() {
    synchronized (this.pathResourceTypeProviders) {
      for (ServiceReference reference : delayedPathResourceTypeProviders) {
        this.addPathResourceTypeProvider(reference);
      }
      delayedPathResourceTypeProviders.clear();
      updateResourceTypeProvidersArray();
    }
  }

  protected void addPathResourceTypeProvider(final ServiceReference reference) {
    final Long id = (Long) reference.getProperty(Constants.SERVICE_ID);
    long ranking = -1;
    if (reference.getProperty(Constants.SERVICE_RANKING) != null) {
      ranking = (Long) reference.getProperty(Constants.SERVICE_RANKING);
    }
    this.pathResourceTypeProviders.add(new PathResourceTypeProviderEntry(id, ranking,
        (PathResourceTypeProvider) this.componentContext.locateService(
            "PathResourceTypeProvider", reference)));
    Collections.sort(this.pathResourceTypeProviders,
        new Comparator<PathResourceTypeProviderEntry>() {

          public int compare(PathResourceTypeProviderEntry o1,
              PathResourceTypeProviderEntry o2) {
            if (o1.ranking < o2.ranking) {
              return 1;
            } else if (o1.ranking > o2.ranking) {
              return -1;
            } else {
              if (o1.serviceId < o2.serviceId) {
                return -1;
              } else if (o1.serviceId > o2.serviceId) {
                return 1;
              }
            }
            return 0;
          }
        });

  }

  /**
     * 
     */
  private void updateResourceTypeProvidersArray() {
    PathResourceTypeProvider[] providers = null;
    log.info("Resource Type Providers is : {} {} ", pathResourceTypeProviders.size(), Arrays.toString(pathResourceTypeProviders.toArray()));
    if (this.pathResourceTypeProviders.size() > 0) {
      providers = new PathResourceTypeProvider[this.pathResourceTypeProviders.size()];
      int index = 0;
      Iterator<PathResourceTypeProviderEntry> i = this.pathResourceTypeProviders
          .iterator();
      log.info("Got Iterator {} from Entries {}  ",i, pathResourceTypeProviders);
      while (i.hasNext()) {
        providers[index++] = i.next().provider;
        log.info("Added {} at {} ",providers[index-1],index-1);
      }
    }
    log.info("Loaded Path Resource Type Providers: {} ",Arrays.toString(providers));
    pathResourceTypeProvidersArray = providers;
  }

  protected void bindPathResourceTypeProvider(ServiceReference reference) {
    synchronized (this.pathResourceTypeProviders) {
      if (componentContext == null) {
        delayedPathResourceTypeProviders.add(reference);
      } else {
        this.addPathResourceTypeProvider(reference);
        updateResourceTypeProvidersArray();

      }
    }
  }

  protected void unbindPathResourceTypeProvider(ServiceReference reference) {
    synchronized (this.pathResourceTypeProviders) {
      delayedPathResourceTypeProviders.remove(reference);
      final long id = (Long) reference.getProperty(Constants.SERVICE_ID);
      final Iterator<PathResourceTypeProviderEntry> i = this.pathResourceTypeProviders
          .iterator();
      while (i.hasNext()) {
        final PathResourceTypeProviderEntry current = i.next();
        if (current.serviceId == id) {
          i.remove();
        }
      }
      updateResourceTypeProvidersArray();
    }
  }

  // ---------- internal helper ----------------------------------------------

  /** Returns the JCR repository used by this factory */
  protected SlingRepository getRepository() {
    return repository;
  }

  protected static final class JcrResourceTypeProviderEntry {
    final long serviceId;

    final long ranking;

    final JcrResourceTypeProvider provider;

    public JcrResourceTypeProviderEntry(final long id, final long ranking,
        final JcrResourceTypeProvider p) {
      this.serviceId = id;
      this.ranking = ranking;
      this.provider = p;
    }
  }

  protected static final class PathResourceTypeProviderEntry {
    final long serviceId;

    final long ranking;

    final PathResourceTypeProvider provider;

    public PathResourceTypeProviderEntry(final long id, final long ranking,
        final PathResourceTypeProvider p) {
      this.serviceId = id;
      this.ranking = ranking;
      this.provider = p;
    }
  }

  private String getServiceName(ServiceReference reference) {
    if (log.isDebugEnabled()) {
      StringBuilder snBuilder = new StringBuilder(64);
      snBuilder.append('{');
      snBuilder.append(reference.toString());
      snBuilder.append('/');
      snBuilder.append(reference.getProperty(Constants.SERVICE_ID));
      snBuilder.append('}');
      return snBuilder.toString();
    }

    return null;
  }

}
