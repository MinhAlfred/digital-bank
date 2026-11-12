---
name: implement-banking-feature
description: Triển khai hoặc sửa tính năng nghiệp vụ trong digital-bank, từ xác định service đến kiểm thử. Dùng khi thay đổi hành vi ứng dụng; review độc lập và migration thuần túy có skill riêng.
---

# Triển khai tính năng ngân hàng

## Xác định phạm vi

- Đọc `AGENTS.md` áp dụng và trạng thái Git trước khi sửa. Xác định hành vi cần đạt, service sở hữu dữ liệu và những thay đổi đang có của người dùng.
- Tài khoản, tiền tệ và số dư thuộc `core-banking-service`; hồ sơ khách hàng thuộc `customer-service`; định tuyến và lọc xác thực thuộc `gateway-service`. Kiểm tra mã nguồn trước khi giả định các service còn lại đã có nghiệp vụ.
- Đọc luồng gần nhất trong `src/main/java/`, migration và test của service. Chỉ hỏi thêm nếu thiếu thông tin làm thay đổi kết quả nghiệp vụ.

## Thực hiện

- Theo cấu trúc package hiện có; tách xử lý HTTP, nghiệp vụ và persistence. Giữ API giữa các service tương thích hoặc nêu rõ thay đổi contract.
- Khi sửa mở tài khoản, đối chiếu `AccountService`, `Account.openCustomerAccount` và `AccountStatusHistory`: xác minh khách hàng trước transaction ghi dữ liệu; lưu tài khoản và lịch sử nguyên tử. Không suy rộng thứ tự này cho mọi quy trình phân tán.
- Với thay đổi số dư, kiểm tra đơn vị tiền, giới hạn số học và kiểm soát cập nhật đồng thời. Giữ ngoại lệ hiện có cho tài khoản SYSTEM khi áp dụng ràng buộc số dư.
- Chỉ thêm kiểm thử cho hành vi bị ảnh hưởng, gồm trường hợp thành công và trường hợp bị từ chối có ý nghĩa. Không tính test chỉ có comment là bằng chứng.

## Kiểm chứng và bàn giao

Từ thư mục service, chạy `./mvnw test` hoặc `.\mvnw.cmd test`; chọn `-Dtest=TenTest` khi phù hợp. Nếu mã có lỗi biên dịch từ trước, phân biệt rõ với lỗi do thay đổi mới, không mở rộng phạm vi sửa một cách mặc định.

Báo cáo hành vi đã thay đổi, file chính, kết quả kiểm tra và phần chưa xác minh. Ví dụ yêu cầu: “Thêm API xem tài khoản của khách hàng đang đăng nhập”.
