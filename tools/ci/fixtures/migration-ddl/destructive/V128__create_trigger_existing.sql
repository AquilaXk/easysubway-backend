CREATE TRIGGER guard_reports BEFORE INSERT ON facility_reports FOR EACH ROW EXECUTE FUNCTION guard_reports();
