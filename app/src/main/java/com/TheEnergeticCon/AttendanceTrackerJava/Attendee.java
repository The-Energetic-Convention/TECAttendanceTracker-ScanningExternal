package com.TheEnergeticCon.AttendanceTrackerJava;

import java.util.Date;
import java.util.List;

public class Attendee
{
    public Attendee(String Name, int ID, Boolean AtCon, List<Date> JoinDates, List<Date> LeaveDates)
    {
        name = Name;
        id = ID;
        atCon = AtCon;
        joinDates = JoinDates;
        leaveDates = LeaveDates;
    }

    public String name;
    public int id;
    public Boolean atCon;
    public List<Date> joinDates;
    public List<Date> leaveDates;
}
