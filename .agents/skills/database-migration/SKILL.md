---
name: database-migration
description: Tạo hoặc review Flyway migration PostgreSQL trong digital-bank khi thay đổi bảng, cột, index, constraint hoặc dữ liệu seed. Không dùng cho thay đổi Java không liên quan schema.
---

# Thay đổi schema PostgreSQL

## Đọc schema liên quan

- Xác định service sở hữu bảng; đọc `src/main/resources/db/migration/`, entity, repository và test liên quan trong service đó.
- Đọc toàn bộ các version tác động đến bảng trước khi chọn version tiếp theo. Mỗi service có lịch sử Flyway riêng.
- Đối chiếu ràng buộc hiện có: tài khoản CUSTOMER phải có chủ sở hữu và số dư không âm; SYSTEM không có `customer_id` và có thể có số dư âm. Khách hàng và tài khoản nằm ở database riêng, không thêm foreign key xuyên service.

## Soạn migration

- Đặt tên `V<version>__<description>.sql`. Không sửa migration đã chạy trên database dùng chung; nếu chưa rõ trạng thái, chọn migration mới.
- Với cột bắt buộc hoặc constraint mới, xác định cách xử lý dữ liệu cũ trước khi áp dụng. Đánh giá khóa bảng và tính tương thích với phiên bản ứng dụng đang chạy theo quy mô thay đổi.
- Giữ entity và SQL nhất quán về kiểu dữ liệu, nullability, enum và version. Dùng `BIGINT` cho tiền theo đơn vị nhỏ nhất; không chuyển sang số thực.

## Kiểm chứng

- Dùng PostgreSQL thật hoặc Testcontainers để kiểm tra SQL đặc thù PostgreSQL. Với thay đổi core banking, tham khảo `AccountSchemaTest` và chạy `.\mvnw.cmd -Dtest=AccountSchemaTest test` từ `core-banking-service` khi Docker sẵn sàng.
- Thêm assertion cho constraint bị ảnh hưởng; flush hoặc thực thi SQL để lỗi database thực sự xuất hiện. Tách các ca vi phạm nếu transaction đã bị đánh dấu rollback.
- Khi cần kiểm tra nâng cấp dữ liệu, dùng database thử nghiệm có schema phiên bản trước và dữ liệu đại diện; không coi chạy trên database rỗng là đủ. Không chạy migration lên database dùng chung nếu yêu cầu chỉ là tạo hoặc review file.

Bàn giao file migration, tác động dữ liệu, kết quả test và cách phục hồi phù hợp nếu có rủi ro mất dữ liệu. Ví dụ yêu cầu: “Thêm index phục vụ tra cứu lịch sử trạng thái tài khoản”.
