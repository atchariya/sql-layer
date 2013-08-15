/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.foundationdb.server.expression.std;

import org.junit.Before;
import com.foundationdb.junit.ParameterizationBuilder;
import com.foundationdb.junit.Parameterization;
import java.util.Collection;
import com.foundationdb.junit.NamedParameterizedRunner.TestParameters;
import com.foundationdb.server.expression.Expression;
import com.foundationdb.junit.NamedParameterizedRunner;
import com.foundationdb.server.expression.ExpressionComposer;
import com.foundationdb.server.types.AkType;
import com.foundationdb.server.types.extract.ConverterTestUtils;
import com.foundationdb.sql.parser.TernaryOperatorNode;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.foundationdb.server.expression.std.ExprUtil.*;
import static org.junit.Assert.*;
import static com.foundationdb.server.expression.std.TimestampDiffExpressionTest.Type.*;

@RunWith(NamedParameterizedRunner.class)
public class TimestampDiffExpressionTest extends ComposedExpressionTestBase
{ 
    @Before
    public void init()
    {
        ConverterTestUtils.setGlobalTimezone("UTC");
    }
    
    static enum Type
    {
        YEAR(lit(TernaryOperatorNode.YEAR_INTERVAL)),
        MONTH(lit(TernaryOperatorNode.MONTH_INTERVAL)),
        QUARTER(lit(TernaryOperatorNode.QUARTER_INTERVAL)),
        WEEK(lit(TernaryOperatorNode.WEEK_INTERVAL)),
        DAY(lit(TernaryOperatorNode.DAY_INTERVAL)),
        HOUR(lit(TernaryOperatorNode.HOUR_INTERVAL)),
        MINUTE(lit(TernaryOperatorNode.MINUTE_INTERVAL)),
        SECOND(lit(TernaryOperatorNode.SECOND_INTERVAL)),
        MILLIS(lit(TernaryOperatorNode.FRAC_SECOND_INTERVAL));
        
        Type(Expression t)
        {
            type = t;
        }
        
        Expression type;
    }
    
    private static boolean alreadyExc = false;
    
    private Type intervalType;
    private Expression date1;
    private Expression date2;
    private Long expected;
    
    public TimestampDiffExpressionTest(Type type, Expression d1, Expression d2, Long exp)
    {
        intervalType = type;
        date1 = d1;
        date2 = d2;
        expected = exp;
    }
    
    @TestParameters
    public static Collection<Parameterization> params()
    {
        ParameterizationBuilder p = new ParameterizationBuilder();
        
        // smoke tests
        param(p, YEAR, lit("2012-11-07"), lit("2006-11-07"), 6L);
        param(p, YEAR, lit("2006-11-07"), lit("2012-11-07"), -6L);
        param(p, QUARTER, lit("2012-11-07"), lit("2006-11-07"), 18L);
        param(p, QUARTER, lit("2006-11-07"), lit("2012-11-07"), -18L);
        param(p, MONTH, lit("2012-11-07"), lit("2006-11-07"), 72L);
        param(p, MONTH, lit("2006-11-07"), lit("2012-11-07"), -72L);
        
        param(p, DAY, lit("1991-05-10 19:30:10"), lit("1991-05-09"), 1L);
        param(p, DAY, lit("1995-06-30"), lit("1995-06-15"), 15L);
        param(p, WEEK, lit("1995-06-30"), lit("1995-06-15"), 2L);
        
        // tricky cases
        param(p, MONTH, lit("2011-01-05"), lit("2010-12-06"), 0L); // 1 day less than 1 month => still 0 MONTH difference
        param(p, MONTH, lit("2010-12-06"), lit("2011-01-05"), 0L); // ditto
        
        param(p, MONTH, lit("2006-11-30"), lit("2005-12-01"), 11L); // 1 day less than 12 months => still 11 MONTH diff
        param(p, MONTH, lit("2005-12-07"), lit("2006-11-07"), -11L); // ditto
        param(p, QUARTER,lit("2005-12-07"), lit("2006-11-07"), -2L); // 2.75 QUARTERs => 2 QUARTERs 
        param(p, YEAR, lit("2005-12-07"), lit("2006-11-07"), 0L); // 11 months, not yet a year
        
        param(p, DAY, lit("1991-05-10 19:30:10"), lit("1991-05-09 19:30:11"), 0L); // 23 hrs, 59 mins and 59 secs => still not a day
        param(p, HOUR, lit("1991-05-10 19:30:10"), lit("1991-05-09 19:30:11"), 23L); // 23 hrs
        param(p, MINUTE, lit("1991-05-10 19:30:10"), lit("1991-05-09 19:30:11"), 23 * 60 + 59L); // 59 mins
        param(p, SECOND, lit("1991-05-10 19:30:10"), lit("1991-05-09 19:30:11"), 23 * 60 * 60 + 59 * 60 + 59L); // 59 secs
        
        // test with nulls
        for (Type t : Type.values())
        {
            param(p, t, LiteralExpression.forNull(), lit("2009-12-12"), null);
            param(p, t, lit("2009-12-12"), LiteralExpression.forNull(), null);
        }
        
        // test invalid date/time values
        param(p, DAY, lit("2009-13-01"), lit("2012-01-01"), null);
        param(p, WEEK, lit("2009-11-32"), lit("2012-01-01"), null);
        param(p, YEAR, lit("2012-01-01"), lit("2009-02-29"),null);
        
        return p.asList();
    }
    
    private static void param (ParameterizationBuilder p, Type interval, Expression date1, Expression date2, Long exp)
    {
        p.add("TIMESTAMPDIFF(" + interval + ", " + date2 + ", " + date1 + ") ",
                interval,
                date2,
                date1,
                exp);
    }
    
    @Test
    public void test()
    {
        Expression top = new TimestampDiffExpression(Arrays.asList(intervalType.type,
                                                                   date1,
                                                                   date2));
        
        if (expected == null)
            assertTrue("Top should be NULL ", top.evaluation().eval().isNull());
        else
            assertEquals(expected.longValue(), top.evaluation().eval().getLong());
    }
    
    @Override
    protected CompositionTestInfo getTestInfo()
    {
        return new CompositionTestInfo(3, AkType.LONG, true);
    }

    @Override
    protected ExpressionComposer getComposer()
    {
        return TimestampDiffExpression.COMPOSER;
    }

    @Override
    public boolean alreadyExc()
    {
        return alreadyExc;
    }
    
}