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
 * This interface represents an electric resistance.
 * The system unit for this quantity is "Ω" (Ohm).
 * 
 * @author  <a href="mailto:jean-marie@dautelle.com">Jean-Marie Dautelle</a>
 * @version 1.0, January 14, 2006
 */
public interface ElectricResistance extends Quantity<ElectricResistance> {

    /**
     * Holds the SI unit (Système International d'Unités) for this quantity.
     */
    public final static Unit<ElectricResistance> SI_UNIT = SI.OHM;

}