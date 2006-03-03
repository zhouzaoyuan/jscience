/*
 * JScience - Java(TM) Tools and Libraries for the Advancement of Sciences.
 * Copyright (C) 2006 - JScience (http://jscience.org/)
 * All rights reserved.
 * 
 * Permission to use, copy, modify, and distribute this software is
 * freely granted, provided that this notice is preserved.
 */
package javax.quantities;
import javax.units.Unit;
import javax.units.SI;

/**
 * This class represents the degree of hotness or coldness of a body or
 * an environment. The system unit for this quantity is "K" (Kelvin).
 * 
 * @author  <a href="mailto:jean-marie@dautelle.com">Jean-Marie Dautelle</a>
 * @version 1.0, January 14, 2006
 */
public interface Temperature extends Quantity<Temperature> {

    /**
     * Holds the SI unit (Système International d'Unités) for this quantity.
     */
    public final static Unit<Temperature> SI_UNIT = SI.KELVIN;

}