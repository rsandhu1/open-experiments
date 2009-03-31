package org.sakaiproject.kernel2.osgi.jpahelloworld.model;

import org.eclipse.persistence.jpa.osgi.PersistenceProvider;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import java.util.HashMap;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

public class Hello implements BundleActivator {

  private static final String PERSISTENCE_UNIT_NAME = "greetingPU";
  private EntityManagerFactory emf;
  private EntityManager em;

  private void createAndRead() {
    Greeting g = new Greeting();
    g.setId(1L);
    g.setMessage("hello, createAndRead");
    em.getTransaction().begin();
    em.persist(g);
    em.getTransaction().commit();

    // g should be written to database now.
    // Read it from db (no transaction context needed for em.find method)
    Greeting g2 = em.find(Greeting.class, g.getId());
    System.out.println("Greeting " + g.getId() + " from db: " + g2);
  }

  private void createAndRollback() {
    Greeting g = new Greeting();
    g.setId(2L);
    g.setMessage("hello, createAndRollback");
    em.getTransaction().begin();
    em.persist(g);
    em.getTransaction().rollback();

    System.out.println("Persisted " + g + ", but the transaction was rolled back.");
    Greeting g2 = em.find(Greeting.class, g.getId());
    System.out.println("Greeting " + g.getId() + " from db: " + g2); // should
                                                                     // be null
  }

  private void initEntityManager() {
    Map<String, Object> properties = new HashMap<String, Object>();
    properties.put("eclipselink.ddl-generation", "drop-and-create-tables");
    properties.put("eclipselink.ddl-generation.output-mode", "database");
    properties.put("eclipselink.classloader", this.getClass().getClassLoader());
    emf = new PersistenceProvider()
        .createEntityManagerFactory("comics", properties);

    emf = Persistence.createEntityManagerFactory(PERSISTENCE_UNIT_NAME);
    em = emf.createEntityManager();
  }

  public void start(BundleContext arg0) throws Exception {
    initEntityManager();
    createAndRead();
    createAndRollback();
  }

  public void stop(BundleContext arg0) throws Exception {
    // TODO Auto-generated method stub

  }
}