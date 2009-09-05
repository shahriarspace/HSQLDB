-- TESTS FOR DATETIME AND INTERVAL
/*r
 1999-12-31 00:00:00.900000
*/CALL TIMESTAMP '2000-01-01 02:02:03.000000' - INTERVAL '01 02:02:02.1' DAY TO SECOND;
/*r
 1999-12-31 00:00:00.000000
*/CALL TIMESTAMP '2000-01-01 02:02:02.123456' - INTERVAL '01 02:02:02.123456' DAY TO SECOND(6);
/*e*//CALL TIMESTAMP(6) '2000-01-01 02:02:02.123456';
/*r
 1999-12-31 00:00:00.023456
*/CALL TIMESTAMP '2000-01-01 02:02:02.123456' - INTERVAL '01 02:02:02.10' DAY TO SECOND;
/*r
 1999-12-31 00:00:00.020
*/CALL TIMESTAMP '2000-01-01 02:02:02.120' - INTERVAL '01 02:02:02.10' DAY TO SECOND;
/*r
 -0 00:01:00.0
*/CALL  INTERVAL '01 02:02:02.1' DAY TO SECOND - INTERVAL '01 02:03:02.1' DAY TO SECOND;
/*r
  1 02:02:00.02000000
*/CALL  INTERVAL '01 02:02:02.12' DAY(3) TO SECOND(8) - INTERVAL '02.1' SECOND(2,3);
/*r
  1 02:02:00.0
*/CALL  INTERVAL '01 02:02:02.12345' DAY(2) TO SECOND(1) - INTERVAL '02.1' SECOND(2,1);
/*r
  1 00:00:00.000000
*/CALL (TIMESTAMP '2000-01-02 02:02:03.000000' - TIMESTAMP '2000-01-01 02:02:03.000000') DAY TO SECOND;

/*r
  1 00:00:00.180000
*/CALL (TIMESTAMP '2000-01-02 02:02:03.2' - TIMESTAMP '2000-01-01 02:02:03.02') DAY TO SECOND;
